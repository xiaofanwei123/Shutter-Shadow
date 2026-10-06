package com.xfw.shuttershadow.client;

import com.xfw.shuttershadow.network.CameraSessionCloseC2S;
import io.github.mortuusars.exposure.client.capture.Capture;
import io.github.mortuusars.exposure.client.capture.action.CaptureAction;
import io.github.mortuusars.exposure.client.capture.action.CompositeAction;
import io.github.mortuusars.exposure.client.image.Image;
import io.github.mortuusars.exposure.util.cycles.task.Result;
import io.github.mortuusars.exposure.util.cycles.task.Task;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 红石照片复用 Exposure 原生截图；本类只限制源支架视角并回报完成。 */
public final class SourceStandCapture extends Capture<Image> {
    private static final Map<Long, SourceStandCapture> ACTIVE = new HashMap<>();
    private static ScopeAction renderingScope;

    private final CameraStandEntity stand;
    private final ClientPacketListener connection = Minecraft.getInstance().getConnection();
    private final NativeScreenshot screenshot;

    public SourceStandCapture(long sequence, CameraStandEntity stand, Task<Result<Image>> screenshot,
                              CaptureAction[] actions) {
        this(sequence, stand, new NativeScreenshot(screenshot), actions, new ScopeAction(stand));
    }

    private SourceStandCapture(long sequence, CameraStandEntity stand, NativeScreenshot screenshot,
                               CaptureAction[] actions, ScopeAction scope) {
        super(screenshot, actionsWithScope(actions, scope));
        this.stand = stand;
        this.screenshot = screenshot;
        ACTIVE.put(sequence, this);
        completableFuture.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            ACTIVE.remove(sequence, this);
            scope.clear();
            if (connection == Minecraft.getInstance().getConnection()) {
                PacketDistributor.sendToServer(new CameraSessionCloseC2S(sequence,
                        error == null && result != null && result.isSuccessful()));
            }
        }));
    }

    private static CaptureAction actionsWithScope(CaptureAction[] actions, ScopeAction scope) {
        CaptureAction[] scoped = Arrays.copyOf(actions, actions.length + 1);
        scoped[actions.length] = scope;
        return new CompositeAction(scoped);
    }

    /** 仅匹配原生截图临时切到的原支架镜头，不影响普通玩家画面或手动取景。 */
    public static boolean isRenderingSourceScene() {
        ScopeAction scope = renderingScope;
        if (scope == null) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.getCameraEntity() == scope.stand && mc.level == scope.stand.level();
    }

    @Override
    public void tick() {
        if (isDone()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || connection != mc.getConnection() || stand.isRemoved()
                || mc.level != stand.level()) {
            cancel();
            return;
        }
        super.tick();
    }

    public static void cancel(long sequence) {
        SourceStandCapture capture = ACTIVE.get(sequence);
        if (capture != null) capture.cancel();
    }

    /** Exposure 尚未建立截图对象便拒绝参数时，服务端也必须收到失败结果。 */
    public static void failBeforeCapture(long sequence) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        mc.execute(() -> {
            if (connection == mc.getConnection()) {
                PacketDistributor.sendToServer(new CameraSessionCloseC2S(sequence, false));
            }
        });
    }

    static void cancelAll() {
        List.copyOf(ACTIVE.values()).forEach(SourceStandCapture::cancel);
        ACTIVE.clear();
        renderingScope = null;
    }

    private void cancel() {
        if (isDone()) return;
        timer.pause();
        Result<Image> error = Result.error(ERROR_FAILED_GENERIC);
        if (screenshot.future != null) {
            // 已开始的原生截图通过原完成链恢复镜头、滤镜和 HUD。
            screenshot.future.complete(error);
        } else {
            setDone();
            completableFuture.complete(error);
        }
    }

    private static final class ScopeAction implements CaptureAction {
        private final CameraStandEntity stand;

        private ScopeAction(CameraStandEntity stand) { this.stand = stand; }

        @Override public void beforeCapture() { renderingScope = this; }
        @Override public void afterCapture() { clear(); }

        private void clear() {
            if (renderingScope == this) renderingScope = null;
        }
    }

    /** 不更改原生 direct/background 选择，只保存取消所需的原完成 future。 */
    private static final class NativeScreenshot extends Task<Result<Image>> {
        private final Task<Result<Image>> delegate;
        private CompletableFuture<Result<Image>> future;

        private NativeScreenshot(Task<Result<Image>> delegate) { this.delegate = delegate; }

        @Override public CompletableFuture<Result<Image>> execute() {
            future = delegate.execute();
            return future;
        }

        @Override public void tick() { delegate.tick(); }
    }
}

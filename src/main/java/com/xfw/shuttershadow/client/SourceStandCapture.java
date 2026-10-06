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

/** 红石支架源维度后台照片。 */
public final class SourceStandCapture extends Capture<Image> {
    private static final Map<Long, SourceStandCapture> ACTIVE = new HashMap<>();
    private static ScopeAction renderingScope;

    private final CameraStandEntity stand;
    private final ClientPacketListener connection = Minecraft.getInstance().getConnection();
    private final NativeScreenshot screenshot;

    /** 把原截图Task包装NativeScreenshot，为支架创建ScopeAction，委托完整构造器。 */
    public SourceStandCapture(long sequence, CameraStandEntity stand, Task<Result<Image>> screenshot,
                              CaptureAction[] actions) {
        this(sequence, stand, new NativeScreenshot(screenshot), actions, new ScopeAction(stand));
    }

    /** 给原actions末尾追加作用域action并登记ACTIVE。 */
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

    /** 复制原action数组追加ScopeAction，返回CompositeAction。 */
    private static CaptureAction actionsWithScope(CaptureAction[] actions, ScopeAction scope) {
        CaptureAction[] scoped = Arrays.copyOf(actions, actions.length + 1);
        scoped[actions.length] = scope;
        return new CompositeAction(scoped);
    }

    /** 当前scope存在且真实cameraEntity/level仍为源支架时标记源场渲染。 */
    public static boolean isRenderingSourceScene() {
        ScopeAction scope = renderingScope;
        if (scope == null) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.getCameraEntity() == scope.stand && mc.level == scope.stand.level();
    }

    /** 推进源维度拍摄，支架失效或玩家离开时取消任务。 */
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

    /** 按序号找到对应任务并取消。 */
    public static void cancel(long sequence) {
        SourceStandCapture capture = ACTIVE.get(sequence);
        if (capture != null) capture.cancel();
    }

    /** 截图创建前失败时排主线程向同连接发送false回执。 */
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

    /** 以快照取消所有ACTIVE并清renderingScope。 */
    static void cancelAll() {
        List.copyOf(ACTIVE.values()).forEach(SourceStandCapture::cancel);
        ACTIVE.clear();
        renderingScope = null;
    }

    /** 暂停计时，已有截图future完成失败。 */
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

    /** 源照片before/after作用域标记，供远场及源照片玩家隐藏Mixin判断。 */
    private static final class ScopeAction implements CaptureAction {
        private final CameraStandEntity stand;

        /** 保存源支架引用。 */
        private ScopeAction(CameraStandEntity stand) { this.stand = stand; }

        /** 截图前把本scope设为全局当前源场scope。 */
        @Override public void beforeCapture() { renderingScope = this; }
        /** 截图后清scope。 */
        @Override public void afterCapture() { clear(); }

        /** 仅当前仍为本scope时置null，避免误清嵌套/后续任务。 */
        private void clear() {
            if (renderingScope == this) renderingScope = null;
        }
    }

    /** 保留原生Exposure截图Task并暴露其future供取消。 */
    private static final class NativeScreenshot extends Task<Result<Image>> {
        private final Task<Result<Image>> delegate;
        private CompletableFuture<Result<Image>> future;

        /** 保存原截图delegate。 */
        private NativeScreenshot(Task<Result<Image>> delegate) { this.delegate = delegate; }

        /** 执行原Task并保存/返回future。 */
        @Override public CompletableFuture<Result<Image>> execute() {
            future = delegate.execute();
            return future;
        }

        /** 把tick转交原Task。 */
        @Override public void tick() { delegate.tick(); }
    }
}

package com.xfw.shuttershadow.client;

import com.xfw.shuttershadow.network.CameraSessionCloseC2S;
import com.xfw.shuttershadow.network.RemoteSceneStartS2C;
import com.xfw.shuttershadow.Shuttershadow;
import io.github.mortuusars.exposure.client.capture.Capture;
import io.github.mortuusars.exposure.client.capture.action.CaptureAction;
import io.github.mortuusars.exposure.client.capture.action.CompositeAction;
import io.github.mortuusars.exposure.client.capture.action.HideGuiAction;
import io.github.mortuusars.exposure.client.capture.action.SetCameraEntityAction;
import io.github.mortuusars.exposure.client.capture.task.BackgroundScreenshotCaptureTask;
import io.github.mortuusars.exposure.client.image.Image;
import io.github.mortuusars.exposure.util.TranslatableError;
import io.github.mortuusars.exposure.util.cycles.task.Result;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;
import com.xfw.shuttershadow.access.IEGameRenderer;
import com.xfw.shuttershadow.access.IECamera;

import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 手动支架远景照片：保留取景器画面，复用 Exposure 的截图和图像处理。 */
public final class RemoteStandCapture extends Capture<Image> {
    private static final Map<Long, RemoteStandCapture> ACTIVE = new HashMap<>();
    /** 只在手动远景截图的同步调用内存在。 */
    private static PreparedScreenshot renderingScreenshot;

    private final ClientPacketListener connection = Minecraft.getInstance().getConnection();

    public RemoteStandCapture(RemoteSceneStartS2C scene, CameraStandEntity stand, CaptureAction[] actions) {
        this(scene, stand, new CompositeAction(Arrays.stream(actions)
                // 离屏截图只渲染世界，不修改玩家的 F1 状态或跨 tick 镜头。
                .filter(action -> !(action instanceof HideGuiAction)
                        && !(action instanceof SetCameraEntityAction))
                .toArray(CaptureAction[]::new)));
    }

    private RemoteStandCapture(RemoteSceneStartS2C scene, CameraStandEntity stand,
                               CaptureAction actions) {
        super(new PreparedScreenshot(scene, stand, actions), delayOnly(actions));
        ACTIVE.put(scene.sequence(), this);
        // 延迟仍由 Exposure 的摄影动作决定，截图不再等待额外区块或暖场帧。
        timer.whenEnded(() -> capturingTask.execute().whenComplete((result, error) ->
                finish(error == null ? result : Result.error(ERROR_FAILED_GENERIC))));
        completableFuture.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            ACTIVE.remove(scene.sequence(), this);
            if (connection == Minecraft.getInstance().getConnection()) {
                PacketDistributor.sendToServer(new CameraSessionCloseC2S(scene.sequence(),
                        error == null && result != null && result.isSuccessful()));
            }
        }));
    }

    public static void cancel(long sequence) {
        RemoteStandCapture capture = ACTIVE.get(sequence);
        if (capture != null) capture.fail(ERROR_FAILED_GENERIC);
    }

    static void cancelAll() {
        List.copyOf(ACTIVE.values()).forEach(capture -> capture.fail(ERROR_FAILED_GENERIC));
        ACTIVE.clear();
    }

    /** 仅供手动远景照片的纹理恢复，不作用于红石源维度照片。 */
    public static boolean isRenderingScreenshot() {
        return renderingScreenshot != null;
    }

    private static CaptureAction delayOnly(CaptureAction actions) {
        return new CaptureAction() {
            @Override public int requiredDelayTicks() { return actions.requiredDelayTicks(); }
            @Override public void initialize() { actions.initialize(); }
            @Override public void delayTick(int ticks) { actions.delayTick(ticks); }
        };
    }

    @Override
    public void tick() {
        if (isDone()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || connection != mc.getConnection()) {
            fail(ERROR_FAILED_GENERIC);
            return;
        }
        super.tick();
    }

    private void fail(TranslatableError error) {
        timer.pause();
        finish(Result.error(error));
    }

    private void finish(Result<Image> result) {
        if (isDone()) return;
        setDone();
        completableFuture.complete(result);
    }

    private static final class PreparedScreenshot extends BackgroundScreenshotCaptureTask {
        private final RemoteSceneStartS2C scene;
        private final CameraStandEntity stand;
        private final CaptureAction actions;

        private PreparedScreenshot(RemoteSceneStartS2C scene, CameraStandEntity stand,
                                   CaptureAction actions) {
            this.scene = scene;
            this.stand = stand;
            this.actions = actions;
        }

        @Override
        public CompletableFuture<Result<Image>> execute() {
            try {
                return CompletableFuture.completedFuture(captureFrame());
            } catch (RuntimeException exception) {
                Shuttershadow.LOGGER.error("Manual remote photo {} failed during drawing.", scene.sequence(), exception);
                return CompletableFuture.completedFuture(Result.error(ERROR_FAILED_GENERIC));
            }
        }

        /** 临时镜头、远景和动作在同一次同步截图中恢复，不跨帧持有状态。 */
        private Result<Image> captureFrame() {
            Minecraft mc = Minecraft.getInstance();
            Camera originalCamera = mc.gameRenderer.getMainCamera();
            var originalEntity = mc.getCameraEntity();
            var originalType = mc.options.getCameraType();
            var originalHit = mc.hitResult;
            var originalPick = mc.crosshairPickEntity;
            IEGameRenderer renderer = (IEGameRenderer) mc.gameRenderer;
            Camera temporaryCamera = new Camera();
            ((IECamera) temporaryCamera).ip_setCameraY(stand.getEyeHeight(), stand.getEyeHeight());
            renderer.ip_setCamera(temporaryCamera);
            mc.cameraEntity = stand;
            ImmersiveCameraClient.beginScreenshot(scene, stand);
            renderingScreenshot = this;
            try {
                actions.beforeCapture();
                return super.execute().join();
            } finally {
                try {
                    actions.afterCapture();
                } finally {
                    mc.cameraEntity = originalEntity;
                    mc.options.setCameraType(originalType);
                    mc.hitResult = originalHit;
                    mc.crosshairPickEntity = originalPick;
                    renderer.ip_setCamera(originalCamera);
                    ImmersiveCameraClient.endScreenshot(scene.sequence());
                    renderingScreenshot = null;
                }
            }
        }
    }
}

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

/** 手动支架目标维度背景截图任务。 */
public final class RemoteStandCapture extends Capture<Image> {
    private static final Map<Long, RemoteStandCapture> ACTIVE = new HashMap<>();
    /** 只在手动远景截图的同步调用内存在。 */
    private static PreparedScreenshot renderingScreenshot;

    private final ClientPacketListener connection = Minecraft.getInstance().getConnection();

    /** 过滤会长期改变界面的HideGuiAction和SetCameraEntityAction，组合其余动作后委托私有构造器。 */
    public RemoteStandCapture(RemoteSceneStartS2C scene, CameraStandEntity stand, CaptureAction[] actions) {
        this(scene, stand, new CompositeAction(Arrays.stream(actions)
                .filter(action -> !(action instanceof HideGuiAction)
                        && !(action instanceof SetCameraEntityAction))
                .toArray(CaptureAction[]::new)));
    }

    /** 创建目标维度截图任务并登记完成回调。 */
    private RemoteStandCapture(RemoteSceneStartS2C scene, CameraStandEntity stand,
                               CaptureAction actions) {
        super(new PreparedScreenshot(scene, stand, actions), delayOnly(actions));
        ACTIVE.put(scene.sequence(), this);
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

    /** 按序号找到任务并失败结束。 */
    public static void cancel(long sequence) {
        RemoteStandCapture capture = ACTIVE.get(sequence);
        if (capture != null) capture.fail(ERROR_FAILED_GENERIC);
    }

    /** 取消并清除全部手动支架截图任务。 */
    static void cancelAll() {
        List.copyOf(ACTIVE.values()).forEach(capture -> capture.fail(ERROR_FAILED_GENERIC));
        ACTIVE.clear();
    }

    /** 判断是否正在绘制手动支架的目标维度截图。 */
    public static boolean isRenderingScreenshot() {
        return renderingScreenshot != null;
    }

    /** 仅转发拍摄延迟动作，让截图任务自行执行拍摄前后的效果。 */
    private static CaptureAction delayOnly(CaptureAction actions) {
        return new CaptureAction() /** 代理延迟动作，避免提前影响玩家当前画面。 */ {
            /** 返回原拍摄动作要求的延迟刻数。 */
            @Override public int requiredDelayTicks() { return actions.requiredDelayTicks(); }
            /** 转发延时初始化。 */
            @Override public void initialize() { actions.initialize(); }
            /** 转发每一刻的延迟动作。 */
            @Override public void delayTick(int ticks) { actions.delayTick(ticks); }
        };
    }

    /** 推进延迟拍摄，玩家离开或连接变化时终止任务。 */
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

    /** 暂停计时，以指定错误结束截图任务。 */
    private void fail(TranslatableError error) {
        timer.pause();
        finish(Result.error(error));
    }

    /** 仅完成一次任务并提交截图结果。 */
    private void finish(Result<Image> result) {
        if (isDone()) return;
        setDone();
        completableFuture.complete(result);
    }

    /** 执行单次目标维度截图并确保恢复渲染状态。 */
    private static final class PreparedScreenshot extends BackgroundScreenshotCaptureTask {
        private final RemoteSceneStartS2C scene;
        private final CameraStandEntity stand;
        private final CaptureAction actions;

        /** 保存固定场景、支架和捕获动作。 */
        private PreparedScreenshot(RemoteSceneStartS2C scene, CameraStandEntity stand,
                                   CaptureAction actions) {
            this.scene = scene;
            this.stand = stand;
            this.actions = actions;
        }

        /** 执行截图并返回完成结果，绘制异常时返回失败。 */
        @Override
        public CompletableFuture<Result<Image>> execute() {
            try {
                return CompletableFuture.completedFuture(captureFrame());
            } catch (RuntimeException exception) {
                Shuttershadow.LOGGER.error("Manual remote photo {} failed during drawing.", scene.sequence(), exception);
                return CompletableFuture.completedFuture(Result.error(ERROR_FAILED_GENERIC));
            }
        }

        /** 临时使用支架镜头截取目标维度画面，完成后恢复原渲染状态。 */
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

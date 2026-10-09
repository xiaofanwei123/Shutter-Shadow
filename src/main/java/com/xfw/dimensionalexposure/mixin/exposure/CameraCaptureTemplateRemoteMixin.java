package com.xfw.dimensionalexposure.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.xfw.dimensionalexposure.client.RemoteStandCapture;
import com.xfw.dimensionalexposure.client.SourceStandCapture;
import com.xfw.dimensionalexposure.network.CameraSessionCloseC2S;
import com.xfw.dimensionalexposure.network.CameraCaptureFailedC2S;
import com.xfw.dimensionalexposure.network.RemoteSceneStartS2C;
import io.github.mortuusars.exposure.client.capture.Capture;
import io.github.mortuusars.exposure.client.capture.action.CaptureAction;
import io.github.mortuusars.exposure.client.capture.template.CameraCaptureTemplate;
import io.github.mortuusars.exposure.client.image.Image;
import io.github.mortuusars.exposure.util.cycles.task.Result;
import io.github.mortuusars.exposure.util.cycles.task.Task;
import io.github.mortuusars.exposure.util.cycles.task.EmptyTask;
import io.github.mortuusars.exposure.world.camera.capture.CaptureParameters;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import com.xfw.dimensionalexposure.core.ClientWorldLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 按拍摄参数选择手动目标维度截图或红石源维度截图。 */
@Mixin(value = CameraCaptureTemplate.class, remap = false)
public abstract class CameraCaptureTemplateRemoteMixin {
    /** 包裹 createTask 的 ClientLevel.getEntity：源照片事务校验当前支架仍有效。 */
    @WrapOperation(method = "createTask", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/multiplayer/ClientLevel;getEntity(I)Lnet/minecraft/world/entity/Entity;",
            remap = true))
    private Entity dimensionalExposure$sourceCameraHolder(ClientLevel level, int id, Operation<Entity> original,
            @Local(argsOnly = true) CaptureParameters parameters) {
        if (parameters.extraData().get(CameraSessionCloseC2S.SOURCE_CAPTURE_SEQUENCE).isPresent()) {
            Entity holder = original.call(level, id);
            // 让无效红石支架进入 Exposure 原生 EmptyTask 出口，避免强制类型转换异常。
            return holder instanceof CameraStandEntity stand && stand.isAlive() && !stand.isRemoved()
                    && stand.getCamera().getItem() instanceof CameraItem ? stand : null;
        }
        RemoteSceneStartS2C scene = parameters.extraData().get(RemoteSceneStartS2C.CAPTURE_SCENE).orElse(null);
        if (scene == null) return original.call(level, id);
        ClientLevel source = ClientWorldLoader.getOptionalWorld(
                ResourceKey.create(Registries.DIMENSION, scene.sourceDimension()));
        return source == null ? null : source.getEntity(id);
    }
    /** 根据拍摄参数创建红石源世界截图或手动远景截图任务。 */
    @WrapOperation(method = "createTask", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/client/capture/Capture;of(Lio/github/mortuusars/exposure/util/cycles/task/Task;[Lio/github/mortuusars/exposure/client/capture/action/CaptureAction;)Lio/github/mortuusars/exposure/client/capture/Capture;"))
    private Capture<Image> dimensionalExposure$remoteStandShot(Task<Result<Image>> screenshot,
            CaptureAction[] actions, Operation<Capture<Image>> original,
            @Local(argsOnly = true) CaptureParameters parameters,
            @Local Entity cameraHolder) {
        Long sourceSequence = parameters.extraData().get(CameraSessionCloseC2S.SOURCE_CAPTURE_SEQUENCE)
                .orElse(null);
        if (sourceSequence != null) {
            return new SourceStandCapture(sourceSequence, (CameraStandEntity) cameraHolder, screenshot, actions);
        }
        RemoteSceneStartS2C scene = parameters.extraData().get(RemoteSceneStartS2C.CAPTURE_SCENE).orElse(null);
        if (scene == null) return original.call(screenshot, actions);
        return new RemoteStandCapture(scene, cameraHolder,
                parameters.extraData().get(RemoteSceneStartS2C.CAPTURE_VIEW).orElseThrow(), actions);
    }

    /** 原生空任务和异步处理失败时报告原连接，保留 Exposure 已有错误处理。 */
    @Inject(method = "createTask", at = @At("RETURN"), cancellable = true)
    private void dimensionalExposure$reportCaptureFailure(CaptureParameters parameters,
                                                    CallbackInfoReturnable<Task<?>> callback) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        Runnable failed = () -> minecraft.execute(() -> {
            if (connection != null && connection == minecraft.getConnection()) {
                PacketDistributor.sendToServer(new CameraCaptureFailedC2S(parameters.exposureId()));
            }
        });
        Task<?> task = callback.getReturnValue();
        if (task instanceof EmptyTask<?>) {
            parameters.extraData().get(CameraSessionCloseC2S.SOURCE_CAPTURE_SEQUENCE)
                    .ifPresent(SourceStandCapture::failBeforeCapture);
            failed.run();
        } else {
            callback.setReturnValue(task.onError(error -> failed.run()));
        }
    }
}

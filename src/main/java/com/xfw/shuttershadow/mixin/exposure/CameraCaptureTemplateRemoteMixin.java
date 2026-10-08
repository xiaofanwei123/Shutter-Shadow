package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.xfw.shuttershadow.client.CameraImageEvents;
import com.xfw.shuttershadow.client.RemoteStandCapture;
import com.xfw.shuttershadow.client.SourceStandCapture;
import com.xfw.shuttershadow.network.CameraSessionCloseC2S;
import com.xfw.shuttershadow.network.CameraCaptureFailedC2S;
import com.xfw.shuttershadow.network.RemoteSceneStartS2C;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/** 按拍摄参数选择手动目标维度截图或红石源维度截图。 */
@Mixin(value = CameraCaptureTemplate.class, remap = false)
public abstract class CameraCaptureTemplateRemoteMixin {
    /** 包裹 createTask 的 ClientLevel.getEntity：源照片事务校验当前支架仍有效。 */
    @WrapOperation(method = "createTask", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/multiplayer/ClientLevel;getEntity(I)Lnet/minecraft/world/entity/Entity;",
            remap = true))
    private Entity shuttershadow$sourceCameraHolder(ClientLevel level, int id, Operation<Entity> original,
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
    @WrapOperation(method = "createTask", at = @At(value = "INVOKE", ordinal = 0, target =
            "Lio/github/mortuusars/exposure/client/capture/Capture;of(Lio/github/mortuusars/exposure/util/cycles/task/Task;[Lio/github/mortuusars/exposure/client/capture/action/CaptureAction;)Lio/github/mortuusars/exposure/client/capture/Capture;"))
    private Capture<Image> shuttershadow$remoteStandShot(Task<Result<Image>> screenshot,
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
        return new RemoteStandCapture(scene, cameraHolder, actions);
    }

    /** 在两个原生图片来源的颜色处理前发布主线程图片事件，保留失败任务原出口。 */
    @WrapOperation(method = "createTask", at = {
            @At(value = "INVOKE", ordinal = 0, target =
                    "Lio/github/mortuusars/exposure/util/cycles/task/Task;thenAsync(Ljava/util/function/Function;)Lio/github/mortuusars/exposure/util/cycles/task/Task;"),
            @At(value = "INVOKE", ordinal = 2, target =
                    "Lio/github/mortuusars/exposure/util/cycles/task/Task;thenAsync(Ljava/util/function/Function;)Lio/github/mortuusars/exposure/util/cycles/task/Task;")})
    private Task<Image> shuttershadow$imageReady(Task<Image> images, Function<Image, Image> effects,
            Operation<Task<Image>> original, @Local(argsOnly = true) CaptureParameters parameters,
            @Local Entity cameraHolder) {
        RemoteSceneStartS2C scene = parameters.extraData().get(RemoteSceneStartS2C.CAPTURE_SCENE).orElse(null);
        ResourceLocation sourceDimension = scene == null
                ? cameraHolder.level().dimension().location() : scene.sourceDimension();
        return original.call(CameraImageEvents.beforeEffects(images, parameters, sourceDimension,
                cameraHolder.getId()), effects);
    }

    /** 原生空任务和异步处理失败时报告原连接，保留 Exposure 已有错误处理。 */
    @Inject(method = "createTask", at = @At("RETURN"), cancellable = true)
    private void shuttershadow$reportCaptureFailure(CaptureParameters parameters,
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

package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.xfw.shuttershadow.client.RemoteStandCapture;
import com.xfw.shuttershadow.client.SourceStandCapture;
import com.xfw.shuttershadow.network.CameraSessionCloseC2S;
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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 手动照片使用远景；红石照片保留 Exposure 原生源维度截图和后处理。 */
@Mixin(value = CameraCaptureTemplate.class, remap = false)
public abstract class CameraCaptureTemplateRemoteMixin {
    /** 玩家跨维度后仍从原支架所在的 IP 世界查询实体，复用 Exposure 后续全部管线。 */
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
        return new RemoteStandCapture(scene, (CameraStandEntity) cameraHolder, actions);
    }

    /** 原生空任务没有失败回调，显式结束尚未创建截图对象的红石事务。 */
    @Inject(method = "createTask", at = @At("RETURN"))
    private void shuttershadow$rejectEmptySourceCapture(CaptureParameters parameters,
                                                        CallbackInfoReturnable<Task<?>> callback) {
        if (!(callback.getReturnValue() instanceof EmptyTask<?>)) return;
        parameters.extraData().get(CameraSessionCloseC2S.SOURCE_CAPTURE_SEQUENCE)
                .ifPresent(SourceStandCapture::failBeforeCapture);
    }
}

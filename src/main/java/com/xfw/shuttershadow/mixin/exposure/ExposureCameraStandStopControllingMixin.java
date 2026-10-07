package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 延迟支架实体的相机复位，避免换维后锁在旧支架视角。 */
@Mixin(value = CameraStandEntity.class, remap = false)
public abstract class ExposureCameraStandStopControllingMixin {
    /** 只延迟本地维度支架的复位，其他支架保留原调用及包装链。 */
    @WrapOperation(method = "stopControlling", at = @At(value = "INVOKE",
            target = "Lio/github/mortuusars/exposure/client/camera/CameraClient;resetCameraEntity()V"))
    private void shuttershadow$deferStandReset(Operation<Void> original) {
        if (!ImmersiveCameraClient.deferExposureStandCameraReset((CameraStandEntity) (Object) this)) {
            original.call();
        }
    }
}

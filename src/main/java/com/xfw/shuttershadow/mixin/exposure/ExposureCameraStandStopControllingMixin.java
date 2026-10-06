package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 延迟支架实体的相机复位，避免换维后锁在旧支架视角。 */
@Mixin(value = CameraStandEntity.class, remap = false)
public abstract class ExposureCameraStandStopControllingMixin {
    /** 将支架实体的相机复位延迟到安全的客户端刻。 */
    @Redirect(method = "stopControlling", at = @At(value = "INVOKE",
            target = "Lio/github/mortuusars/exposure/client/camera/CameraClient;resetCameraEntity()V"))
    private void shuttershadow$deferStandReset() {
        ImmersiveCameraClient.deferExposureStandCameraReset();
    }
}

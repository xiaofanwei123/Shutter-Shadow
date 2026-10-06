package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Defers the second camera reset inside Exposure's stand entity itself.
 * ClientPacketsHandler.stopControllingCameraStand() calls
 * CameraStandEntity.stopControlling(), which has its own reset call in
 * addition to the packet handler call redirected by the companion mixin.
 */
@Mixin(value = CameraStandEntity.class, remap = false)
public abstract class ExposureCameraStandStopControllingMixin {
    @Redirect(method = "stopControlling", at = @At(value = "INVOKE",
            target = "Lio/github/mortuusars/exposure/client/camera/CameraClient;resetCameraEntity()V"))
    private void shuttershadow$deferStandReset() {
        ImmersiveCameraClient.deferExposureStandCameraReset();
    }
}

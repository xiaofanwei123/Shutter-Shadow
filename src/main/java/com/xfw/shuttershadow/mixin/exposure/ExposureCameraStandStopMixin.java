package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import io.github.mortuusars.exposure.network.handler.ClientPacketsHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps Exposure's stand-stop packet from changing Minecraft.cameraEntity in
 * the middle of a render/portal transition. The actual reset is performed at
 * the next client tick boundary by ImmersiveCameraClient.
 */
@Mixin(value = ClientPacketsHandler.class, remap = false)
public abstract class ExposureCameraStandStopMixin {
    @Redirect(method = "stopControllingCameraStand", at = @At(value = "INVOKE",
            target = "Lio/github/mortuusars/exposure/client/camera/CameraClient;setCameraEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private static void shuttershadow$deferCameraReset(Entity ignored) {
        ImmersiveCameraClient.deferExposureStandCameraReset();
    }
}

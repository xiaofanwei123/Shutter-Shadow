package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import io.github.mortuusars.exposure.network.handler.ClientPacketsHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 将停止支架控制包接入统一的相机安全恢复流程。 */
@Mixin(value = ClientPacketsHandler.class, remap = false)
public abstract class ExposureCameraStandStopMixin {
    /** 延迟停止控制包中的相机复位，避免恢复到旧世界实体。 */
    @Redirect(method = "stopControllingCameraStand", at = @At(value = "INVOKE",
            target = "Lio/github/mortuusars/exposure/client/camera/CameraClient;setCameraEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private static void shuttershadow$deferCameraReset(Entity ignored) {
        ImmersiveCameraClient.deferExposureStandCameraReset();
    }
}

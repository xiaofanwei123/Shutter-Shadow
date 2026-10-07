package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.mortuusars.exposure.network.handler.ClientPacketsHandler;
import io.github.mortuusars.exposure.network.packet.clientbound.CameraStandStopControllingS2CP;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 将停止支架控制包接入统一的相机安全恢复流程。 */
@Mixin(value = ClientPacketsHandler.class, remap = false)
public abstract class ExposureCameraStandStopMixin {
    /** 维度支架延后复位，其余停止控制包沿用原方法。 */
    @WrapOperation(method = "stopControllingCameraStand", at = @At(value = "INVOKE",
            target = "Lio/github/mortuusars/exposure/client/camera/CameraClient;setCameraEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private static void shuttershadow$deferCameraReset(Entity entity, Operation<Void> original,
                                                      CameraStandStopControllingS2CP packet) {
        Minecraft mc = Minecraft.getInstance();
        Entity stand = mc.level == null ? null : mc.level.getEntity(packet.standId());
        if (!ImmersiveCameraClient.deferExposureStandCameraReset(stand)) {
            original.call(entity);
        }
    }
}

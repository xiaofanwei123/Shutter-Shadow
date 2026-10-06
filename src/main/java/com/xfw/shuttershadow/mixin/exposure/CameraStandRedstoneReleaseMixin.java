package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.xfw.shuttershadow.network.RemoteStandPreparation;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.entity.CameraStandRedstoneControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 在支架真实红石快门调用期间标记触发来源。 */
@Mixin(value = CameraStandRedstoneControl.class, remap = false)
public abstract class CameraStandRedstoneReleaseMixin {
    /** 标记真实红石释放来源，再执行原版支架快门。 */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/world/entity/CameraStandEntity;release()V"))
    private void shuttershadow$markRedstoneRelease(CameraStandEntity stand, Operation<Void> original) {
        RemoteStandPreparation.redstoneRelease(stand, () -> original.call(stand));
    }
}

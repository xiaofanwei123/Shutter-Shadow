package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.xfw.shuttershadow.network.RemoteStandPreparation;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.entity.CameraStandRedstoneControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 从红石控制器的真实释放调用标记来源，避免用操作员或取景器状态猜测。 */
@Mixin(value = CameraStandRedstoneControl.class, remap = false)
public abstract class CameraStandRedstoneReleaseMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/world/entity/CameraStandEntity;release()V"))
    private void shuttershadow$markRedstoneRelease(CameraStandEntity stand, Operation<Void> original) {
        RemoteStandPreparation.redstoneRelease(stand, () -> original.call(stand));
    }
}

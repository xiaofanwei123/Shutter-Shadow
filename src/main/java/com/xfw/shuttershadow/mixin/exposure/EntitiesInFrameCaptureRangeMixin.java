package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.xfw.shuttershadow.util.CaptureEntitySearchRange;
import io.github.mortuusars.exposure.world.camera.frame.EntitiesInFrame;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 只覆盖本次拍摄的查询范围，筛选、排序及遮挡继续由 Exposure 实现。 */
@Mixin(value = EntitiesInFrame.class, remap = false)
public abstract class EntitiesInFrameCaptureRangeMixin {
    @WrapOperation(method = "get(Lnet/minecraft/world/entity/Entity;Lio/github/mortuusars/exposure/util/PointOfView;D)Ljava/util/List;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;inflate(D)Lnet/minecraft/world/phys/AABB;",
                    remap = true))
    private static AABB shuttershadow$captureRange(AABB box, double radius, Operation<AABB> original) {
        return original.call(box, CaptureEntitySearchRange.currentOr(radius));
    }
}

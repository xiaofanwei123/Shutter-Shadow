package com.xfw.dimensionalexposure.mixin.minecraft.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.xfw.dimensionalexposure.DimensionalExposure;
import com.xfw.dimensionalexposure.util.CountDownInt;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 为原版视锥偏移循环增加上限，防止异常投影导致卡死。 */
@Mixin(Frustum.class)
public abstract class MixinFrustum_FixDeadLoop {
    private static final CountDownInt dimensionalExposure$logLimit = new CountDownInt(10);

    /** 保留原版偏移计算与包装链，完成十次修正后停止异常循环。 */
    @WrapOperation(method = "offsetToFullyIncludeCameraCube(I)Lnet/minecraft/client/renderer/culling/Frustum;",
            at = @At(value = "INVOKE", target = "Lorg/joml/FrustumIntersection;intersectAab(FFFFFF)I"))
    private int dimensionalExposure$limitCameraCubeOffset(FrustumIntersection intersection,
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
            Operation<Integer> original, @Share("dimensionalExposure$cameraCubeChecks") LocalIntRef checks) {
        int count = checks.get();
        if (count >= 10) {
            if (dimensionalExposure$logLimit.tryDecrement()) {
                DimensionalExposure.LOGGER.error("the projection matrix and the frustum are abnormal", new Throwable());
                if (dimensionalExposure$logLimit.isZero()) {
                    DimensionalExposure.LOGGER.info("The logging reached its limit. Similar log won't be displayed.");
                }
            }
            return FrustumIntersection.INSIDE;
        }
        checks.set(count + 1);
        return original.call(intersection, minX, minY, minZ, maxX, maxY, maxZ);
    }
}

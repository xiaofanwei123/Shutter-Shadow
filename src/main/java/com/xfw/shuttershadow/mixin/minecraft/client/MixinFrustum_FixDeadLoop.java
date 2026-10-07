package com.xfw.shuttershadow.mixin.minecraft.client;

import com.xfw.shuttershadow.Shuttershadow;


import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import com.xfw.shuttershadow.core.VanillaRuntimeHooks;
import com.xfw.shuttershadow.util.CountDownInt;

/** 为原版视锥偏移循环增加上限，防止异常投影导致卡死。 */
@Mixin(Frustum.class)
public abstract class MixinFrustum_FixDeadLoop {
    @Shadow
    private double camX;
    
    @Shadow
    private double camY;
    
    @Shadow
    private double camZ;
    
    @Shadow
    private Vector4f viewVector;
    
    @Shadow @Final private FrustumIntersection intersection;
    private static final CountDownInt shuttershadow$logLimit = new CountDownInt(10);
    
    /**
     * 最多修正十次相机包围盒，防止异常视锥造成死循环。
     * @author qouteall
     * @reason 此循环不易通过注入或重定向修正。
     */
    @Overwrite
    @VanillaRuntimeHooks
    public Frustum offsetToFullyIncludeCameraCube(int gridSize) {
        
        double minX = Math.floor(this.camX / (double) gridSize) * (double) gridSize;
        double minY = Math.floor(this.camY / (double) gridSize) * (double) gridSize;
        double minZ = Math.floor(this.camZ / (double) gridSize) * (double) gridSize;
        double maxX = Math.ceil(this.camX / (double) gridSize) * (double) gridSize;
        double maxY = Math.ceil(this.camY / (double) gridSize) * (double) gridSize;
        double maxZ = Math.ceil(this.camZ / (double) gridSize) * (double) gridSize;
        
        int countLimit = 10; // 限制循环次数。
        
        while (this.intersection.intersectAab((float) (minX - this.camX), (float) (minY - this.camY), (float) (minZ - this.camZ), (float) (maxX - this.camX), (float) (maxY - this.camY), (float) (maxZ - this.camZ))!= -2) {
            this.camX -= (double) (this.viewVector.x() * 4.0F);
            this.camY -= (double) (this.viewVector.y() * 4.0F);
            this.camZ -= (double) (this.viewVector.z() * 4.0F);
            countLimit--;
            if (countLimit <= 0) {
                if (shuttershadow$logLimit.tryDecrement()) {
                    Shuttershadow.LOGGER.error("the projection matrix and the frustum are abnormal", new Throwable());
                    if (shuttershadow$logLimit.isZero()) {
                        Shuttershadow.LOGGER.info("The logging reached its limit. Similar log won't be displayed.");
                    }
                }
                break;
            }
        }
        
        return (Frustum) (Object) this;
    }
}

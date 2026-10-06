package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import com.xfw.shuttershadow.core.VanillaRuntimeHooks;
import com.xfw.shuttershadow.util.Helper;
import com.xfw.shuttershadow.util.CountDownInt;

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
     * Make it to not deadloop when using isometric view.
     * Also make it to not deadloop even if the projection matrix is broken. (In normal cases the projection should not be broken.)
     *
     * @author qouteall
     * @reason Hard to do by injection or redirection
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
        
        int countLimit = 10; // limit the loop count
        
        while (this.intersection.intersectAab((float) (minX - this.camX), (float) (minY - this.camY), (float) (minZ - this.camZ), (float) (maxX - this.camX), (float) (maxY - this.camY), (float) (maxZ - this.camZ))!= -2) {
            this.camX -= (double) (this.viewVector.x() * 4.0F);
            this.camY -= (double) (this.viewVector.y() * 4.0F);
            this.camZ -= (double) (this.viewVector.z() * 4.0F);
            countLimit--;
            if (countLimit <= 0) {
                if (shuttershadow$logLimit.tryDecrement()) {
                    Helper.err("the projection matrix and the frustum are abnormal");
                    new Throwable().printStackTrace();
                    if (shuttershadow$logLimit.isZero()) {
                        Helper.log("The logging reached its limit. Similar log won't be displayed.");
                    }
                }
                break;
            }
        }
        
        return (Frustum) (Object) this;
    }
}

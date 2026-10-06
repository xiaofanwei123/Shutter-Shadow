package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.access.IECamera;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

@Mixin(Camera.class)
// Shuttershadow 第六轮：仅保留远景相机位置与状态访问，撤销穿门实体视角处理。
public abstract class MixinCamera implements IECamera {
    
    @Shadow
    private BlockGetter level;
    @Shadow
    private float eyeHeight;
    @Shadow
    private float eyeHeightOld;
    
    @Shadow
    protected abstract void setPosition(Vec3 vec3d_1);
    
    @Inject(
        method = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
        at = @At("RETURN")
    )
    private void onUpdateFinished(
        BlockGetter area, Entity focusedEntity, boolean thirdPerson,
        boolean inverseView, float partialTick, CallbackInfo ci
    ) {
        Camera this_ = (Camera) (Object) this;
        WorldRenderInfo.adjustCameraPos(this_);
    }
    
    
    
    @Override
    public void ip_resetState(Vec3 pos, ClientLevel currWorld) {
        setPosition(pos);
        level = currWorld;
    }
    
    @Override
    public void ip_setCameraY(float cameraY_, float lastCameraY_) {
        eyeHeight = cameraY_;
        eyeHeightOld = lastCameraY_;
    }
    
    @Override
    public void portal_setPos(Vec3 pos) {
        setPosition(pos);
    }
    
}

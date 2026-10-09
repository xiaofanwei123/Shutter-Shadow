package com.xfw.dimensionalexposure.mixin.minecraft.client;


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
import com.xfw.dimensionalexposure.access.IECamera;
import com.xfw.dimensionalexposure.core.render.WorldRenderInfo;

/** 提供相机状态访问，并按远景上下文修正观察位置。 */
@Mixin(value = Camera.class, priority = 500)
public abstract class MixinCamera implements IECamera {
    
    @Shadow
    private BlockGetter level;
    @Shadow
    private float eyeHeight;
    @Shadow
    private float eyeHeightOld;
    
    /** Shadow 引用 Camera 原 setPosition，供以下桥方法调用，不是额外位置算法。 */
    @Shadow
    protected abstract void setPosition(Vec3 vec3d_1);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);
    
    /** 按当前远景渲染信息修正相机位置。 */
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
    
    
    
    /** 同时设置相机坐标和 level，供切换世界后校正 Camera 上下文。 */
    @Override
    public void ip_resetState(Vec3 pos, ClientLevel currWorld) {
        setPosition(pos);
        level = currWorld;
    }
    
    /** 同时设置当前/上次 tick 的 eyeHeight，保持远维度或临时观察实体的相机高度插值一致。 */
    @Override
    public void ip_setCameraY(float cameraY_, float lastCameraY_) {
        eyeHeight = cameraY_;
        eyeHeightOld = lastCameraY_;
    }
    
    /** 只通过原 setPosition 设置坐标，不更改世界。 */
    @Override
    public void portal_setPos(Vec3 pos) {
        setPosition(pos);
    }

    @Override
    public void ip_setRotation(float yaw, float pitch) {
        setRotation(yaw, pitch);
    }
    
}

package com.xfw.dimensionalexposure.mixin.minecraft.client;


import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TrackingEmitter;
import net.minecraft.resources.ResourceLocation;
import com.xfw.dimensionalexposure.client.ImmersiveCameraClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.dimensionalexposure.access.IEParticleManager;
import com.xfw.dimensionalexposure.core.render.RenderStates;
import java.util.Set;

/** 隔离各维度的粒子绘制和更新，避免粒子串入错误世界。 */
@SuppressWarnings("resource")
@Mixin(ParticleEngine.class)
public class MixinParticleEngine implements IEParticleManager {
    @Shadow
    protected ClientLevel level;

    @Unique
    private ClientLevel dimensionalExposure$playerWorld;
    @Unique
    private Set<ResourceLocation> dimensionalExposure$remoteDimensions = Set.of();

    /** 每次粒子更新固定仍在使用的世界，避免逐粒子重复解析相机。 */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickBegin(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        dimensionalExposure$playerWorld = client.player == null ? client.level : (ClientLevel) client.player.level();
        dimensionalExposure$remoteDimensions = ImmersiveCameraClient.particleDimensions();
    }

    /** 原版更换或退出世界清粒子时，一并释放本轮更新缓存。 */
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void onLevelChanged(ClientLevel world, CallbackInfo ci) {
        dimensionalExposure$playerWorld = null;
        dimensionalExposure$remoteDimensions = Set.of();
    }
    
    // 不渲染超出远景有效范围的粒子。
    
    // 此粒子渲染注入可能与 Sodium 或 Iris 发生冲突。
    /** 仅绘制当前渲染状态允许的粒子。 */
    @WrapWithCondition(
        method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"
        )
    )
    private boolean redirectBuildGeometry(
        Particle instance, VertexConsumer vertexConsumer, Camera camera, float v
    ) {
        return RenderStates.shouldRenderParticle(instance);
    }
    
    // 熔岩火星粒子更新时可能继续生成烟雾粒子。
    // 保证新粒子生成在正确维度。
    /** 隔离当前世界更新，并让原版队列与分组计数清理停用维度的粒子。 */
    @Inject(method = "Lnet/minecraft/client/particle/ParticleEngine;tickParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void onTickParticle(Particle particle, CallbackInfo ci) {
        ClientLevel particleWorld = ((IEParticle) particle).portal_getWorld();
        if (particleWorld != Minecraft.getInstance().level) {
            if (!isActiveParticleWorld(particleWorld)) particle.remove();
            ci.cancel();
        }
    }

    /** 跟踪发射器只在所属世界推进一次，停用场景由原版移出队列。 */
    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/TrackingEmitter;tick()V"))
    private boolean shouldTickEmitter(TrackingEmitter emitter) {
        ClientLevel emitterWorld = ((IEParticle) emitter).portal_getWorld();
        if (emitterWorld == Minecraft.getInstance().level) return true;
        if (!isActiveParticleWorld(emitterWorld)) emitter.remove();
        return false;
    }

    /** 保留真实玩家世界与当前远景，兼容无缝传送后的粒子连续显示。 */
    @Unique
    private boolean isActiveParticleWorld(ClientLevel world) {
        return world == dimensionalExposure$playerWorld || (world != null
                && dimensionalExposure$remoteDimensions.contains(world.dimension().location()));
    }
    
    /** 设置粒子引擎 level 引用，供世界切换更新统一引擎所用世界。 */
    @Override
    public void ip_setWorld(ClientLevel world_) {
        level = world_;
    }
    
}

package com.xfw.shuttershadow.mixin.minecraft.client;


import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.access.IEParticleManager;
import com.xfw.shuttershadow.core.render.RenderStates;

/** 隔离各维度的粒子绘制和更新，避免粒子串入错误世界。 */
@SuppressWarnings("resource")
@Mixin(ParticleEngine.class)
public class MixinParticleEngine implements IEParticleManager {
    @Shadow
    protected ClientLevel level;
    
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
    /** 跳过不属于当前世界的粒子更新，避免衍生粒子进入错误维度。 */
    @Inject(method = "Lnet/minecraft/client/particle/ParticleEngine;tickParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void onTickParticle(Particle particle, CallbackInfo ci) {
        if (((IEParticle) particle).portal_getWorld() != Minecraft.getInstance().level) {
            ci.cancel();
        }
    }
    
    /** 设置粒子引擎 level 引用，供世界切换更新统一引擎所用世界。 */
    @Override
    public void ip_setWorld(ClientLevel world_) {
        level = world_;
    }
    
}

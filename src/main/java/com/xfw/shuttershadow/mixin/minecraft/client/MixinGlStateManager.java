package com.xfw.shuttershadow.mixin.minecraft.client;


import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.shuttershadow.core.render.GLResourceCache;

/** 从批量资源缓存分配原版缓冲区和顶点数组。 */
@Mixin(value = GlStateManager.class, remap = false)
public abstract class MixinGlStateManager {
    
    /** 从资源缓存分配缓冲区编号，替代单次生成。 */
    @Inject(
        method = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onGenBuffers(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GLResourceCache.bufferCache.getNewResourceId());
    }
    
    /** 从资源缓存分配顶点数组编号，替代单次生成。 */
    @Inject(
        method = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onGenVertexArrays(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GLResourceCache.vertexArrayCache.getNewResourceId());
    }
}

package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.shuttershadow.core.render.GLResourceCache;

@Mixin(value = GlStateManager.class, remap = false)
public abstract class MixinGlStateManager {
    
    @Inject(
        method = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onGenBuffers(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GLResourceCache.bufferCache.getNewResourceId());
    }
    
    @Inject(
        method = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onGenVertexArrays(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GLResourceCache.vertexArrayCache.getNewResourceId());
    }
}

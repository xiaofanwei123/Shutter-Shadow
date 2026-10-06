package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSystem.class)
public interface IERenderSystem {
    @Accessor(value = "modelViewStack", remap = false)
    public static Matrix4fStack ip_getModelViewStack() {
        throw new RuntimeException();
    }
    
    @Mutable
    @Accessor(value = "modelViewStack", remap = false)
    public static void ip_setModelViewStack(Matrix4fStack arg) {
        throw new RuntimeException();
    }
}

package com.xfw.dimensionalexposure.mixin.minecraft.client;


import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 提供替换全局模型视图栈的内部访问接口。 */
@Mixin(RenderSystem.class)
public interface IERenderSystem {
    /** 静态 Mutable Accessor 替换 modelViewStack 原引用，供世界绘制建立与恢复矩阵上下文。 */
    @Mutable
    @Accessor(value = "modelViewStack", remap = false)
    public static void ip_setModelViewStack(Matrix4fStack arg) {
        throw new RuntimeException();
    }
}

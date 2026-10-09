package com.xfw.dimensionalexposure.mixin.minecraft.client;


import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.xfw.dimensionalexposure.core.CoreSettings;
import com.xfw.dimensionalexposure.core.ClientWorldLoader;
import com.xfw.dimensionalexposure.core.render.WorldRenderInfo;

/** 仅缩小本模组远景资源的初始区段缓冲，普通世界沿用原容量。 */
@Mixin(SectionBufferBuilderPack.class)
public class MixinSectionBufferBuilderPack {
    // 缓冲区可以按需扩容。
    // 无需在创建时分配很大的缓冲区。
    // 原版通常只有一个客户端世界，内存开销较小。
    // 相机同时加载多个维度时，每个维度都会创建一组缓冲区。
    // 过大的初始缓冲可能耗尽内存。
    // 此注入通过缩小初始容量降低内存占用。
    // 初始容量不能为零，因为扩容发生在写入顶点之后。
    /** 保留原容量调用链，仅在渲染线程创建远景资源时按配置减小容量。 */
    @WrapOperation(
        method = "lambda$new$0",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderType;bufferSize()I"
        )
    )
    private static int dimensionalExposure$remoteBufferSize(RenderType instance, Operation<Integer> original) {
        int size = original.call(instance);
        if (!CoreSettings.saveMemoryInBufferPack || !RenderSystem.isOnRenderThread()) return size;
        Minecraft mc = Minecraft.getInstance();
        boolean remote = ClientWorldLoader.getIsCreatingClientWorld() || WorldRenderInfo.isRendering()
                || mc.player != null && mc.level != null && mc.level != mc.player.level();
        return remote ? Math.min(128, size) : size;
    }
}

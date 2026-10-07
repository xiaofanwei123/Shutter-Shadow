package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.compat.SodiumInterface;

/** 为后台世界的原版区段编译器分配独立缓冲池。 */
@Mixin(SectionRenderDispatcher.class)
public class MixinSectionRenderDispatcher {
    /** 为远程世界创建独立编译缓冲池，避免跨线程共享。 */
    @WrapOperation(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderBuffers;sectionBufferPool()Lnet/minecraft/client/renderer/SectionBufferBuilderPool;"
        )
    )
    private SectionBufferBuilderPool shuttershadow$secondaryBufferPool(RenderBuffers instance,
            Operation<SectionBufferBuilderPool> original) {
        if (ClientWorldLoader.getIsCreatingClientWorld()
            && !SodiumInterface.invoker.isSodiumPresent()
        ) {
            int processors = Runtime.getRuntime().availableProcessors();
            return SectionBufferBuilderPool.allocate(processors);
        }
        
        return original.call(instance);
    }
}

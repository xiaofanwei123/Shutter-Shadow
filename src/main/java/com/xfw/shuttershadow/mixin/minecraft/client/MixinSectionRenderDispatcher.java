package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.compat.SodiumInterface;
import com.xfw.shuttershadow.mixin.minecraft.client.MixinSectionBufferBuilderPack;

@Mixin(SectionRenderDispatcher.class)
public class MixinSectionRenderDispatcher {
    /**
     * When loading multiple client dimensions at the same time,
     * there will be multiple {@link SectionRenderDispatcher} instances.
     * They cannot share one {@link SectionBufferBuilderPool} instance because
     * that type is not thread-safe.
     * In {@link MixinSectionBufferBuilderPack} it reduces the initial size of the buffer
     * to reduce memory overhead.
     * This is not enabled in Sodium as Sodium does not use this.
     */
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderBuffers;sectionBufferPool()Lnet/minecraft/client/renderer/SectionBufferBuilderPool;"
        )
    )
    private SectionBufferBuilderPool redirectSectionBufferBuilderPool(RenderBuffers instance) {
        if (ClientWorldLoader.getIsCreatingClientWorld()
            && !SodiumInterface.invoker.isSodiumPresent()
        ) {
            int processors = Runtime.getRuntime().availableProcessors();
            return SectionBufferBuilderPool.allocate(processors);
        }
        
        return instance.sectionBufferPool();
    }
}

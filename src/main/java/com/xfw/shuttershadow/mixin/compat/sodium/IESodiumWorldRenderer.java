package com.xfw.shuttershadow.mixin.compat.sodium;
// Shuttershadow phase seven: relocated into the camera core.

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface IESodiumWorldRenderer {
    @Accessor("renderSectionManager")
    RenderSectionManager ip_getRenderSectionManager();
}

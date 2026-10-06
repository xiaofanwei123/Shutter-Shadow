package com.xfw.shuttershadow.mixin.compat.sodium;


import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 提供读取 Sodium 区段管理器的内部访问接口。 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface IESodiumWorldRenderer {
    /** 读取 Sodium 的区段渲染管理器。 */
    @Accessor("renderSectionManager")
    RenderSectionManager ip_getRenderSectionManager();
}

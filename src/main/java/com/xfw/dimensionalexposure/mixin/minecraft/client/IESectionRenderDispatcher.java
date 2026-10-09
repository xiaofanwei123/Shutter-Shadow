package com.xfw.dimensionalexposure.mixin.minecraft.client;


import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 客户端 SectionRenderDispatcher 固定网格缓冲访问器，支持不同世界编译器上下文切换。 */
@Mixin(SectionRenderDispatcher.class)
public interface IESectionRenderDispatcher {
    /** Accessor 取得 fixedBuffers 实际引用。 */
    @Accessor("fixedBuffers")
    SectionBufferBuilderPack ip_getFixedBuffers();
    
    /** Mutable Accessor 替换 fixedBuffers 引用。 */
    @Mutable
    @Accessor("fixedBuffers")
    void ip_setFixedBuffers(SectionBufferBuilderPack arg);
}

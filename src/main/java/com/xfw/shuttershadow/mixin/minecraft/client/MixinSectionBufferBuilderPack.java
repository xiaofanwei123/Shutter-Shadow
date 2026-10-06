package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.xfw.shuttershadow.core.CoreSettings;

/** 按节省内存配置缩小原版区段缓冲包的初始容量。 */
@Mixin(SectionBufferBuilderPack.class)
public class MixinSectionBufferBuilderPack {
    // 缓冲区可以按需扩容。
    // 无需在创建时分配很大的缓冲区。
    // 原版通常只有一个客户端世界，内存开销较小。
    // 相机同时加载多个维度时，每个维度都会创建一组缓冲区。
    // 过大的初始缓冲可能耗尽内存。
    // 此注入通过缩小初始容量降低内存占用。
    // 初始容量不能为零，因为扩容发生在写入顶点之后。
    /** 构造 lambda 的 RenderType.bufferSize 调用：配置关返回原容量。 */
    @Redirect(
        method = "lambda$new$0",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderType;bufferSize()I"
        )
    )
    private static int redirectBufferSize(RenderType instance) {
        if (!CoreSettings.saveMemoryInBufferPack) {
            return instance.bufferSize();
        }
        
        return Math.min(128, instance.bufferSize());
    }
}

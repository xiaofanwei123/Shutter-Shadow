package com.xfw.shuttershadow.mixin.compat.iris;


import com.mojang.blaze3d.shaders.Program;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.xfw.shuttershadow.core.ClientWorldLoader;

import java.util.Collections;
import java.util.Map;

/** 多世界渲染启用后隔离着色程序缓存，避免跨维度复用。 */
@Mixin(ShaderInstance.class)
public class MixinShaderInstanceForIris {
    // 加载 Iris 时，避免复用缓存中其它维度的着色程序。
    /** 多世界渲染时隔离着色程序缓存，避免跨维度复用。 */
    @Redirect(
        method = "Lnet/minecraft/client/renderer/ShaderInstance;getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Program$Type;getPrograms()Ljava/util/Map;"
        )
    )
    private static Map<String, Program> redirectGetProgramCache(Program.Type type) {
        if (ClientWorldLoader.getIsInitialized()) {
            return Collections.emptyMap();
        }
        return type.getPrograms();
    }
}

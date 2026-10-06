package com.xfw.shuttershadow.mixin.compat.iris;
// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.blaze3d.shaders.Program;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.xfw.shuttershadow.core.ClientWorldLoader;

import java.util.Collections;
import java.util.Map;

@Mixin(ShaderInstance.class)
public class MixinShaderInstanceForIris {
    // if iris is present, avoid reusing other dimensions' program in cache
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

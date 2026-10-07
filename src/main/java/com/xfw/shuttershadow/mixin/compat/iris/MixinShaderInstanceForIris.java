package com.xfw.shuttershadow.mixin.compat.iris;

import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/** 仅隔离 Iris 生成的着色程序，保留其他着色器的原生缓存。 */
@Mixin(ShaderInstance.class)
public class MixinShaderInstanceForIris {
    @Unique private static long shuttershadow$nextProgramId;

    /** Iris 管线独立编译同名程序，普通程序保留缓存和包装链。 */
    @WrapOperation(
        method = "Lnet/minecraft/client/renderer/ShaderInstance;getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Program$Type;getPrograms()Ljava/util/Map;"
        )
    )
    private static Map<String, Program> shuttershadow$programCache(Program.Type type,
            Operation<Map<String, Program>> original, @Local(argsOnly = true) ResourceProvider resources) {
        Map<String, Program> programs = original.call(type);
        return shuttershadow$isIrisProgram(resources) ? Collections.emptyMap() : programs;
    }

    /** 私有缓存名称让原生释放只删除本程序，不污染其他维度或模组的缓存。 */
    @WrapOperation(
        method = "Lnet/minecraft/client/renderer/ShaderInstance;getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/Program;compileShader(Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;Ljava/io/InputStream;Ljava/lang/String;Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;)Lcom/mojang/blaze3d/shaders/Program;")
    )
    private static Program shuttershadow$compileProgram(Program.Type type, String name,
            InputStream input, String sourceName, GlslPreprocessor preprocessor, Operation<Program> original,
            @Local(argsOnly = true) ResourceProvider resources) {
        String cacheName = shuttershadow$isIrisProgram(resources)
                ? "shuttershadow:iris/" + ++shuttershadow$nextProgramId + "/" + name : name;
        return original.call(type, cacheName, input, sourceName, preprocessor);
    }

    /** 按资源的真实创建者识别 Iris 程序，不依赖取景、世界初始化或重载时序。 */
    @Unique
    private static boolean shuttershadow$isIrisProgram(ResourceProvider resources) {
        return resources.getClass().getNestHost() == ShaderCreator.class;
    }
}

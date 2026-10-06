package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.xfw.shuttershadow.DimensionFilterResources;
import io.github.mortuusars.exposure.Exposure;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/** 仅扩展 Exposure 滤镜注册表的文件读取目录；解码和同步仍走原生流程。 */
@Mixin(RegistryDataLoader.class)
public abstract class DimensionFilterDataMixin {
    @WrapOperation(method = "loadContentsFromManager", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/resources/FileToIdConverter;listMatchingResources(Lnet/minecraft/server/packs/resources/ResourceManager;)Ljava/util/Map;"))
    private static Map<ResourceLocation, Resource> shuttershadow$dimensionFiles(
            FileToIdConverter converter, ResourceManager manager,
            Operation<Map<ResourceLocation, Resource>> original,
            @Local(argsOnly = true) WritableRegistry<?> registry) {
        Map<ResourceLocation, Resource> files = original.call(converter, manager);
        return registry.key().equals(Exposure.Registries.FILTER)
                ? DimensionFilterResources.merge(manager, files) : files;
    }

    @WrapOperation(method = "loadContentsFromNetwork", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/ResourceProvider;getResourceOrThrow(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/server/packs/resources/Resource;"))
    private static Resource shuttershadow$knownPackDimensionFile(
            ResourceProvider provider, ResourceLocation file, Operation<Resource> original,
            @Local(argsOnly = true) WritableRegistry<?> registry) {
        if (registry.key().equals(Exposure.Registries.FILTER)) {
            Resource resource = DimensionFilterResources.fromNetwork(provider, file);
            if (resource != null) return resource;
        }
        return original.call(provider, file);
    }
}

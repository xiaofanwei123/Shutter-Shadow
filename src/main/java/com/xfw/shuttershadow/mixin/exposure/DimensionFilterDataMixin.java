package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.xfw.shuttershadow.data.DimensionFilterResources;
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

/** 让 Exposure 维度滤镜注册表读取本模组的数据包目录。 */
@Mixin(RegistryDataLoader.class)
public abstract class DimensionFilterDataMixin {
    /** 将维度滤镜新目录合入文件枚举，并保留数据包优先级。 */
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

    /** 本次网络注册表加载仅合并一次滤镜资源，未命中时回退原资源读取。 */
    @WrapOperation(method = "loadContentsFromNetwork", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/ResourceProvider;getResourceOrThrow(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/server/packs/resources/Resource;"))
    private static Resource shuttershadow$knownPackDimensionFile(
            ResourceProvider provider, ResourceLocation file, Operation<Resource> original,
            @Local(argsOnly = true) WritableRegistry<?> registry,
            @Share("dimensionFilterResources") LocalRef<Map<ResourceLocation, Resource>> resources) {
        if (registry.key().equals(Exposure.Registries.FILTER)) {
            Resource resource = DimensionFilterResources.fromNetwork(provider, file, manager -> {
                Map<ResourceLocation, Resource> merged = resources.get();
                if (merged == null) {
                    merged = DimensionFilterResources.merge(manager);
                    resources.set(merged);
                }
                return merged;
            });
            if (resource != null) return resource;
        }
        return original.call(provider, file);
    }
}

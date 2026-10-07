package com.xfw.shuttershadow;

import io.github.mortuusars.exposure.Exposure;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** 把维度滤镜数据包映射到原生滤镜注册表，沿用原生附件纹理和颜色字段。 */
public final class DimensionFilterResources {
    private static final FileToIdConverter DIMENSION_FILES = FileToIdConverter.json("dimension_filter");
    private static final FileToIdConverter EXPOSURE_FILES =
            FileToIdConverter.json(Registries.elementsDirPath(Exposure.Registries.FILTER));

    /** 禁止实例化此工具类。 */
    private DimensionFilterResources() {}

    /** 扫描本模组命名空间的新路径，验证维度路径，把资源转换为Exposure虚拟文件键，并用包优先级解决同名覆盖。 */
    public static Map<ResourceLocation, Resource> merge(ResourceManager manager,
                                                       Map<ResourceLocation, Resource> exposureFiles) {
        Map<ResourceLocation, Resource> dimensionFiles = DIMENSION_FILES.listMatchingResourcesFromNamespace(
                manager, Shuttershadow.MODID);
        if (dimensionFiles.isEmpty()) return exposureFiles;

        Map<String, Integer> priorities = new HashMap<>();
        for (PackResources pack : manager.listPacks().toList()) {
            priorities.put(pack.packId(), priorities.size());
        }
        Map<ResourceLocation, Resource> merged = new TreeMap<>(exposureFiles);
        dimensionFiles.forEach((file, resource) -> {
            ResourceLocation id = DIMENSION_FILES.fileToId(file);
            if (!hasDimensionPath(id)) return;
            ResourceLocation virtualFile = EXPOSURE_FILES.idToFile(id);
            Resource previous = merged.get(virtualFile);
            if (previous == null || priorities.getOrDefault(resource.sourcePackId(), -1)
                    >= priorities.getOrDefault(previous.sourcePackId(), -1)) {
                merged.put(virtualFile, resource);
            }
        });
        return merged;
    }

    /** 针对从网络收到的Exposure虚拟路径寻找真实资源。 */
    public static @Nullable Resource fromNetwork(ResourceProvider provider, ResourceLocation virtualFile) {
        if (!virtualFile.getNamespace().equals(Shuttershadow.MODID)
                || !virtualFile.getPath().startsWith("exposure/filter/")
                || !virtualFile.getPath().endsWith(".json")) return null;
        ResourceLocation id = EXPOSURE_FILES.fileToId(virtualFile);
        if (!hasDimensionPath(id)) return null;
        if (provider instanceof ResourceManager manager) {
            return merge(manager, EXPOSURE_FILES.listMatchingResources(manager)).get(virtualFile);
        }
        // 一般 ResourceProvider 不公开包顺序：保留可直接读取的标准路径，否则读取新路径。
        return provider.getResource(virtualFile)
                .or(() -> provider.getResource(DIMENSION_FILES.idToFile(id)))
                .orElse(null);
    }

    /** 验证路径能拆成非空维度命名空间和维度路径，且构成合法ResourceLocation。 */
    private static boolean hasDimensionPath(ResourceLocation id) {
        int separator = id.getPath().indexOf('/');
        return separator > 0 && separator < id.getPath().length() - 1
                && ResourceLocation.tryBuild(id.getPath().substring(0, separator),
                id.getPath().substring(separator + 1)) != null;
    }

}

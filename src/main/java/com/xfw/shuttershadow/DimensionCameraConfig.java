package com.xfw.shuttershadow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.xfw.shuttershadow.api.DimensionFilters.Route;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.data.Filter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** 维度路由解析及JSON兜底配置。 */
public final class DimensionCameraConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "shuttershadow_dimensions.json";
    private static volatile DimensionCameraConfig current;
    private static volatile long currentStamp = Long.MIN_VALUE;

    /** 缓存注册表候选列表，每次仍用完整 ItemPredicate 检查物品；不缓存可变物品的匹配结果。 */
    private static final Map<RegistryAccess, List<Filter>> REGISTRY_CACHE =
            new WeakHashMap<>();

    /** 按物品、目标维度组件和来源维度索引路由，区分共享物品编号的滤镜变体。 */
    private final Map<ResourceLocation, Map<ResourceLocation,
            Map<ResourceLocation, DimensionCameraPredicate.Source>>> filters;

    /** 按物品、目标维度和来源维度读取配置，坐标比例省略时为一。 */
    private DimensionCameraConfig(JsonObject root) {
        Map<ResourceLocation, Map<ResourceLocation,
                Map<ResourceLocation, DimensionCameraPredicate.Source>>> parsed = new LinkedHashMap<>();
        JsonObject filterValues = object(root, "filters");
        for (Map.Entry<String, JsonElement> filterEntry : filterValues.entrySet()) {
            if (!filterEntry.getValue().isJsonObject()) continue;
            ResourceLocation filterId = ResourceLocation.tryParse(filterEntry.getKey());
            if (filterId == null) continue;

            JsonObject targets = object(filterEntry.getValue().getAsJsonObject(), "targets");
            Map<ResourceLocation, Map<ResourceLocation, DimensionCameraPredicate.Source>> targetRoutes =
                    new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> targetEntry : targets.entrySet()) {
                if (!targetEntry.getValue().isJsonObject()) continue;
                ResourceLocation targetDimension = ResourceLocation.tryParse(targetEntry.getKey());
                if (targetDimension == null) continue;
                Map<ResourceLocation, DimensionCameraPredicate.Source> sources = parseSources(
                        targetEntry.getValue().getAsJsonObject());
                if (!sources.isEmpty()) {
                    targetRoutes.put(targetDimension, sources);
                }
            }
            if (!targetRoutes.isEmpty()) {
                parsed.put(filterId,
                        Collections.unmodifiableMap(targetRoutes));
            }
        }
        filters = Collections.unmodifiableMap(parsed);
    }

    /** 读取配置文件修改时间。 */
    public static DimensionCameraConfig load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        long stamp = modified(path);
        DimensionCameraConfig result = current;
        if (result != null && stamp == currentStamp) return result;
        synchronized (DimensionCameraConfig.class) {
            stamp = modified(path);
            if (current != null && stamp == currentStamp) return current;
            JsonObject root;
            try {
                if (Files.notExists(path)) {
                    root = defaults();
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, GSON.toJson(root));
                    stamp = modified(path);
                } else {
                    root = GSON.fromJson(Files.readString(path), JsonObject.class);
                    if (root == null) root = defaults();
                }
            } catch (Exception ignored) {
                root = defaults();
            }
            current = new DimensionCameraConfig(root);
            currentStamp = stamp;
            return current;
        }
    }

    /** 按滤镜物品、组件目标、当前来源维度取JSON路由，缺失任意一层返回null。 */
    private Route forFilter(ResourceLocation filter, ResourceLocation target, ResourceLocation source) {
        if (filter == null || target == null || source == null) return null;
        Map<ResourceLocation, Map<ResourceLocation, DimensionCameraPredicate.Source>> targetRoutes =
                filters.get(filter);
        if (targetRoutes == null) return null;
        Map<ResourceLocation, DimensionCameraPredicate.Source> sources = targetRoutes.get(target);
        if (sources == null) return null;
        DimensionCameraPredicate.Source route = sources.get(source);
        return route == null ? null : new Route(filter, target, route.coordinateScale());
    }

    /** 用同一编解码器读取来源数组，与数据包保持一致。 */
    private static Map<ResourceLocation, DimensionCameraPredicate.Source> parseSources(JsonObject target) {
        Map<ResourceLocation, DimensionCameraPredicate.Source> sources = new LinkedHashMap<>();
        DimensionCameraPredicate.CODEC.parse(JsonOps.INSTANCE, target).result().ifPresent(predicate ->
                predicate.routes().forEach(route -> sources.put(route.sourceDimension(), route)));
        return Collections.unmodifiableMap(sources);
    }

    /** 首个匹配滤镜省略来源配置时采用一，否则按显式来源限制解析。 */
    public static Route resolve(RegistryAccess registryAccess, ItemStack filterStack,
                                ResourceLocation sourceDimension) {
        if (filterStack == null || filterStack.isEmpty() || sourceDimension == null) return null;
        ResourceLocation filterId = BuiltInRegistries.ITEM.getKey(filterStack.getItem());
        ResourceLocation targetDimension = filterStack.get(Shuttershadow.DIMENSION_FILTER_TARGET.get());
        if (targetDimension == null || targetDimension.equals(sourceDimension)) return null;
        try {
            if (registryAccess != null) {
                List<Filter> candidates;
                synchronized (REGISTRY_CACHE) {
                    candidates = REGISTRY_CACHE.computeIfAbsent(registryAccess, access ->
                            access.registryOrThrow(Exposure.Registries.FILTER).stream().toList());
                }
                for (Filter filter : candidates) {
                    if (filter.predicate().test(filterStack)) {
                        DimensionCameraPredicate predicate = DimensionCameraPredicate.from(filter);
                        if (predicate == null) return new Route(filterId, targetDimension, 1.0D);
                        DimensionCameraPredicate.Source route = predicate.routeFor(sourceDimension);
                        // 匹配结果是权威配置，不能用本地兜底重新启用未列出的来源。
                        return route == null ? null
                                : new Route(filterId, targetDimension, route.coordinateScale());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 客户端早期加载时使用本地路由表作为兜底。
        }
        return load().forFilter(filterId, targetDimension, sourceDimension);
    }

    /** 创建原版维度的默认配置，下界目标比例为八，其余省略采用一。 */
    private static JsonObject defaults() {
        JsonObject root = new JsonObject();
        JsonObject filters = new JsonObject();
        JsonObject dimensionFilter = new JsonObject();
        JsonObject targets = new JsonObject();
        addTargetSources(targets, "minecraft:the_nether", 8.0D,
                Level.OVERWORLD.location().toString(), "minecraft:the_end");
        addTargetSources(targets, "minecraft:the_end", 1.0D,
                Level.OVERWORLD.location().toString(), "minecraft:the_nether");
        addTargetSources(targets, Level.OVERWORLD.location().toString(), 1.0D,
                "minecraft:the_nether", "minecraft:the_end");
        dimensionFilter.add("targets", targets);
        filters.add(Shuttershadow.DIMENSION_FILTER.getId().toString(), dimensionFilter);
        root.add("filters", filters);
        return root;
    }

    /** 将目标与允许使用滤镜的来源维度分开记录，不重复声明目标。 */
    private static void addTargetSources(JsonObject targets, String target, double coordinateScale,
                                         String... sourceDimensions) {
        JsonObject value = new JsonObject();
        JsonArray routes = new JsonArray();
        for (String sourceDimension : sourceDimensions) {
            JsonObject source = new JsonObject();
            source.addProperty("source_dimension", sourceDimension);
            if (coordinateScale != 1.0D) source.addProperty("coordinate_scale", coordinateScale);
            routes.add(source);
        }
        value.add("routes", routes);
        targets.add(target, value);
    }

    /** 安全取得JSON子对象，缺失或类型错误返回空对象。 */
    private static JsonObject object(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonObject()
                ? root.getAsJsonObject(key) : new JsonObject();
    }

    /** 读取文件最后修改毫秒数。 */
    private static long modified(Path path) {
        try { return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1L; }
        catch (IOException ignored) { return -1L; }
    }

}

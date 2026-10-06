package com.xfw.shuttershadow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.xfw.shuttershadow.api.DimensionFilters;
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
            Map<ResourceLocation, DimensionCameraPredicate.Route>>> filters;

    /** 解析filters→物品ID→targets→目标维度→routes→来源维度，跳过非对象和非法ID，保存只读嵌套映射。 */
    private DimensionCameraConfig(JsonObject root) {
        Map<ResourceLocation, Map<ResourceLocation,
                Map<ResourceLocation, DimensionCameraPredicate.Route>>> parsed = new LinkedHashMap<>();
        JsonObject filterValues = object(root, "filters");
        for (Map.Entry<String, JsonElement> filterEntry : filterValues.entrySet()) {
            if (!filterEntry.getValue().isJsonObject()) continue;
            ResourceLocation filterId = ResourceLocation.tryParse(filterEntry.getKey());
            if (filterId == null) continue;

            JsonObject targets = object(filterEntry.getValue().getAsJsonObject(), "targets");
            Map<ResourceLocation, Map<ResourceLocation, DimensionCameraPredicate.Route>> targetRoutes =
                    new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> targetEntry : targets.entrySet()) {
                if (!targetEntry.getValue().isJsonObject()) continue;
                ResourceLocation targetDimension = ResourceLocation.tryParse(targetEntry.getKey());
                if (targetDimension == null) continue;
                Map<ResourceLocation, DimensionCameraPredicate.Route> routes = parseRoutes(
                        object(targetEntry.getValue().getAsJsonObject(), "routes"));
                if (!routes.isEmpty()) {
                    targetRoutes.put(targetDimension, routes);
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
        Map<ResourceLocation, Map<ResourceLocation, DimensionCameraPredicate.Route>> targetRoutes =
                filters.get(filter);
        if (targetRoutes == null) return null;
        Map<ResourceLocation, DimensionCameraPredicate.Route> routes = targetRoutes.get(target);
        if (routes == null) return null;
        DimensionCameraPredicate.Route route = routes.get(source);
        return route == null ? null : new Route(filter, route.targetDimension(), route.coordinateScale());
    }

    /** 用ROUTE_CODEC解码每条来源路由，只收集成功解码的项，返回只读映射。 */
    private static Map<ResourceLocation, DimensionCameraPredicate.Route> parseRoutes(JsonObject routeValues) {
        Map<ResourceLocation, DimensionCameraPredicate.Route> routes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> routeEntry : routeValues.entrySet()) {
            if (!routeEntry.getValue().isJsonObject()) continue;
            ResourceLocation source = ResourceLocation.tryParse(routeEntry.getKey());
            if (source == null) continue;
            DimensionCameraPredicate.ROUTE_CODEC.parse(JsonOps.INSTANCE, routeEntry.getValue())
                    .result().ifPresent(route -> routes.put(source, route));
        }
        return Collections.unmodifiableMap(routes);
    }

    /** 查找第一个物品谓词命中的Exposure滤镜，从其维度谓词取来源路由。 */
    public static Route resolve(RegistryAccess registryAccess, ItemStack filterStack,
                                ResourceLocation sourceDimension) {
        if (filterStack == null || filterStack.isEmpty() || sourceDimension == null) return null;
        ResourceLocation filterId = BuiltInRegistries.ITEM.getKey(filterStack.getItem());
        ResourceLocation targetDimension = DimensionFilters.target(filterStack);
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
                        if (predicate != null) {
                            DimensionCameraPredicate.Route route = predicate.routeFor(sourceDimension);
                            if (route != null) {
                                return new Route(filterId, route.targetDimension(), route.coordinateScale());
                            }
                        }
                        // 与 Exposure 的 findFirst 一致：不越过先匹配到的普通滤镜。
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 客户端早期加载时使用本地路由表作为兜底。
        }
        return load().forFilter(filterId, targetDimension, sourceDimension);
    }

    /** 构造三种原版目标维度的默认来源路由及coordinate_scale。 */
    private static JsonObject defaults() {
        JsonObject root = new JsonObject();
        JsonObject filters = new JsonObject();
        JsonObject dimensionFilter = new JsonObject();
        JsonObject targets = new JsonObject();
        addTargetRoutes(targets, "minecraft:the_nether",
                route(Level.OVERWORLD.location().toString(), "minecraft:the_nether", 8.0D),
                route("minecraft:the_end", "minecraft:the_nether", 8.0D));
        addTargetRoutes(targets, "minecraft:the_end",
                route(Level.OVERWORLD.location().toString(), "minecraft:the_end", 1.0D),
                route("minecraft:the_nether", "minecraft:the_end", 1.0D));
        addTargetRoutes(targets, Level.OVERWORLD.location().toString(),
                route("minecraft:the_nether", Level.OVERWORLD.location().toString(), 1.0D),
                route("minecraft:the_end", Level.OVERWORLD.location().toString(), 1.0D));
        dimensionFilter.add("targets", targets);
        filters.add(Shuttershadow.DIMENSION_FILTER.getId().toString(), dimensionFilter);
        root.add("filters", filters);
        return root;
    }

    /** 把给定目标的来源路由打包进targets对象。 */
    private static void addTargetRoutes(JsonObject targets, String target, JsonObject... routes) {
        JsonObject value = new JsonObject();
        JsonObject routeMap = new JsonObject();
        for (JsonObject route : routes) {
            routeMap.add(route.get("source_dimension").getAsString(), route);
        }
        value.add("routes", routeMap);
        targets.add(target, value);
    }

    /** 创建含source_dimension、target_dimension、coordinate_scale的JSON对象。 */
    private static JsonObject route(String source, String target, double coordinateScale) {
        JsonObject value = new JsonObject();
        value.addProperty("source_dimension", source);
        value.addProperty("target_dimension", target);
        value.addProperty("coordinate_scale", coordinateScale);
        return value;
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

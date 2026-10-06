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

/** Resolves a filter's source-dimension route and coordinate conversion. */
public final class DimensionCameraConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "shuttershadow_dimensions.json";
    private static volatile DimensionCameraConfig current;
    private static volatile long currentStamp = Long.MIN_VALUE;

    /** 缓存注册表候选列表，每次仍用完整 ItemPredicate 检查物品；不缓存可变物品的匹配结果。 */
    private static final Map<RegistryAccess, List<Filter>> REGISTRY_CACHE =
            new WeakHashMap<>();

    /**
     * Item ID -> target dimension (the dimension-filter component) -> source
     * dimension -> route.  The target dimension has to be part of the key now
     * that all dimension filters share one item ID.
     */
    private final Map<ResourceLocation, Map<ResourceLocation,
            Map<ResourceLocation, DimensionCameraPredicate.Route>>> filters;

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

    /** Loads and caches the local route file, reloading it after an mtime change. */
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

    /** Resolves one source -> target edge from the local route table. */
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

    /**
     * Resolves the route from the Exposure data pack predicate. The local file
     * is a small fallback for the period before the data-pack registry is
     * ready (or when the stack has no matching predicate yet).
     */
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
            // The local route table is the client-side fallback during early loading.
        }
        return load().forFilter(filterId, targetDimension, sourceDimension);
    }

    /** Writes a route-only default configuration for the three vanilla levels. */
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

    private static void addTargetRoutes(JsonObject targets, String target, JsonObject... routes) {
        JsonObject value = new JsonObject();
        JsonObject routeMap = new JsonObject();
        for (JsonObject route : routes) {
            routeMap.add(route.get("source_dimension").getAsString(), route);
        }
        value.add("routes", routeMap);
        targets.add(target, value);
    }

    private static JsonObject route(String source, String target, double coordinateScale) {
        JsonObject value = new JsonObject();
        value.addProperty("source_dimension", source);
        value.addProperty("target_dimension", target);
        value.addProperty("coordinate_scale", coordinateScale);
        return value;
    }

    private static JsonObject object(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonObject()
                ? root.getAsJsonObject(key) : new JsonObject();
    }

    private static long modified(Path path) {
        try { return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1L; }
        catch (IOException ignored) { return -1L; }
    }

}

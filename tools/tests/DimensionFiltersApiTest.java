import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.serialization.JsonOps;
import com.xfw.shuttershadow.DimensionCameraConfig;
import com.xfw.shuttershadow.DimensionCameraPredicate;
import com.xfw.shuttershadow.DimensionFilterResources;
import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.mixin.exposure.DimensionFilterDataMixin;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.data.Filter;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.ItemSubPredicate;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** 执行生产公共入口、Codec、完整物品谓词和加载钩子，不启动 Minecraft。 */
public final class DimensionFiltersApiTest {
    private static final ResourceLocation SOURCE = id("minecraft:overworld");
    private static final ResourceLocation END = id("minecraft:the_end");
    private static final ResourceLocation NETHER = id("minecraft:the_nether");
    private static final ResourceLocation ITEM = id("shuttershadow:dimension_filter");
    private static final Item FOREIGN = new Item(id("test:filter"));
    private static final Object EXTRA_COMPONENT = new Object();
    private static final FileToIdConverter EXPOSURE_FILES = FileToIdConverter.json("exposure/filter");
    private static final ResourceLocation NEW_END = id("shuttershadow:dimension_filter/minecraft/the_end.json");
    private static final ResourceLocation VIRTUAL_END = id("shuttershadow:exposure/filter/minecraft/the_end.json");
    private static final ResourceLocation LEGACY_END = id("shuttershadow:exposure/filter/the_end.json");
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        FMLPaths.CONFIGDIR.directory = Path.of(arguments[1], "config-" + arguments[0]);
        switch (arguments[0]) {
            case "public-api" -> publicApi();
            case "predicate-codec" -> predicateCodec();
            case "stack-state" -> stackState();
            case "registry-snapshot" -> registrySnapshot();
            case "local-fallback" -> localFallback();
            case "resource-paths" -> resourcePaths(Path.of(arguments[2]));
            case "pack-priorities" -> packPriorities();
            case "network-fallback" -> networkFallback();
            case "hook-scope" -> hookScope();
            default -> throw new AssertionError(arguments[0]);
        }
        System.out.println("PASS: " + arguments[0] + " (" + checks + " checks)");
    }

    private static void publicApi() {
        ItemStack filter = DimensionFilters.create(NETHER);
        check(filter.getItem() == Shuttershadow.DIMENSION_FILTER.get(), "create uses the registered unified item");
        check(filter.count == 1 && DimensionFilters.target(filter).equals(NETHER), "create writes only target and one item");
        check(DimensionFilters.create(NETHER) != filter, "create returns independent stacks");
        filter.set(Shuttershadow.DIMENSION_FILTER_TARGET.get(), END);
        check(DimensionFilters.target(filter).equals(END), "target reflects component changes");
        ItemStack foreign = new ItemStack(FOREIGN);
        foreign.set(Shuttershadow.DIMENSION_FILTER_TARGET.get(), NETHER);
        check(DimensionFilters.target(foreign) == null, "a foreign stack cannot forge the unified item's target");
        check(DimensionFilters.target(null) == null && DimensionFilters.target(ItemStack.EMPTY) == null,
                "null and empty stack have no target");
        check(DimensionFilters.resolve(null, null, SOURCE) == null, "null stack has no route");
        check(DimensionFilters.resolve(null, filter, null) == null, "null source has no route");
        var route = new DimensionFilters.Route(ITEM, NETHER, 8);
        check(route.equals(new DimensionFilters.Route(ITEM, NETHER, 8)), "route is a value snapshot");
        check(route.filter().equals(ITEM), "route carries item ID rather than data entry ID");
        check(DimensionFilters.horizontalScale(route, new Level(1), new Level(4)) == 0.125,
                "explicit route target coordinate scale takes precedence over level type");
        check(DimensionFilters.horizontalScale(new DimensionFilters.Route(ITEM, END, Double.NaN), new Level(8), new Level(2)) == 4,
                "omitted scale uses registered dimension type");
        check(DimensionFilters.horizontalScale(null, new Level(8), new Level(1)) == 8, "null route uses vanilla conversion");
        Vec3 position = DimensionFilters.mapAbsolute(new Vec3(80, 72, -40), 0.125);
        check(position.x == 10 && position.y == 72 && position.z == -5, "absolute mapping preserves feet Y");
        Vec3 relative = DimensionFilters.mapRelative(new Vec3(8, 3, -16), new Vec3(10, 70, 20), 0.125, 1.6);
        check(relative.x == 11 && relative.y == 74.6 && relative.z == 18, "relative mapping preserves eye offset");
        for (double invalid : new double[] {0, -1, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            expect(IllegalArgumentException.class, () -> new DimensionFilters.Route(ITEM, END, invalid));
        }
        expect(NullPointerException.class, () -> DimensionFilters.create(null));
        expect(NullPointerException.class, () -> new DimensionFilters.Route(null, END, 1));
        expect(NullPointerException.class, () -> new DimensionFilters.Route(ITEM, null, 1));
    }

    private static void predicateCodec() {
        var predicate = parse("{\"routes\":{\"minecraft:overworld\":{\"target_dimension\":\"test:world\"}}}");
        check(predicate.routeFor(SOURCE).targetDimension().equals(id("test:world")), "custom target decodes");
        check(Double.isNaN(predicate.routeFor(SOURCE).coordinateScale()), "omitted scale preserves registered-type sentinel");
        check(predicate.routeFor(NETHER) == null && predicate.routeFor(null) == null, "absent source has no route");
        JsonObject encoded = DimensionCameraPredicate.CODEC.encodeStart(JsonOps.INSTANCE, predicate).getOrThrow().getAsJsonObject();
        check(!encoded.getAsJsonObject("routes").getAsJsonObject(SOURCE.toString()).has("coordinate_scale"),
                "omitted coordinate scale round trips without non-JSON NaN");
        check(predicate.matches(ItemStack.EMPTY), "metadata predicate does not bypass surrounding ItemPredicate");
        for (String scale : List.of("0", "-1", "1e400", "\"bad\"")) {
            var result = DimensionCameraPredicate.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                    "{\"routes\":{\"minecraft:overworld\":{\"target_dimension\":\"test:world\",\"coordinate_scale\":" + scale + "}}}"));
            check(result.error().isPresent(), "invalid coordinate scale returns codec error: " + scale);
        }
        check(DimensionCameraPredicate.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"routes\":{}}")).error().isPresent(),
                "empty routes produce a codec error instead of escaping constructor");
        check(DimensionCameraPredicate.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"routes\":{\"minecraft:overworld\":{\"target_dimension\":\"Bad Namespace:world\"}}}")).error().isPresent(),
                "invalid resource IDs fail decode");
        Map<ResourceLocation, DimensionCameraPredicate.Route> original = new LinkedHashMap<>();
        original.put(SOURCE, new DimensionCameraPredicate.Route(END, 1));
        var frozen = new DimensionCameraPredicate(original);
        original.clear();
        check(frozen.routeFor(SOURCE) != null, "predicate copies caller's mutable map");
        expect(UnsupportedOperationException.class, () -> frozen.routes().clear());
        check(DimensionCameraPredicate.from(filter(stack -> true, frozen)) == frozen, "type extraction returns camera metadata");
        check(DimensionCameraPredicate.from(new Filter(new ItemPredicate(stack -> true, Map.of()))) == null,
                "ordinary Exposure filter has no dimension metadata");
    }

    private static void stackState() {
        ItemStack stack = new ItemStack(FOREIGN);
        stack.set(Shuttershadow.DIMENSION_FILTER_TARGET.get(), END);
        RegistryAccess registry = registry(
                filter(item -> item.get(EXTRA_COMPONENT) != null, predicate(id("test:component"), 1)),
                filter(item -> item.damage > 0, predicate(id("test:damaged"), 1)),
                filter(item -> item.count > 1, predicate(id("test:multiple"), 1)),
                filter(item -> item.customName.equals("special"), predicate(id("test:named"), 1)),
                filter(item -> true, predicate(id("test:default"), 1)));
        check(target(registry, stack).equals(id("test:default")), "default stack route");
        stack.set(EXTRA_COMPONENT, "variant");
        check(target(registry, stack).equals(id("test:component")), "same ID/target/source with changed component rechecks predicate");
        stack.set(EXTRA_COMPONENT, null);
        stack.damage = 5;
        check(target(registry, stack).equals(id("test:damaged")), "damage changes are rechecked");
        stack.damage = 0; stack.count = 2;
        check(target(registry, stack).equals(id("test:multiple")), "count changes are rechecked");
        stack.count = 1; stack.customName = "special";
        check(target(registry, stack).equals(id("test:named")), "custom name changes are rechecked");
        stack.customName = "";
        check(target(registry, stack).equals(id("test:default")), "restored stack returns to its original route");
        check(registry.queries == 1, "registry candidate enumeration is cached, not mutable stack matches");
        RegistryAccess ordinaryFirst = registry(new Filter(new ItemPredicate(item -> true, Map.of())),
                filter(item -> true, predicate(END, 1)));
        check(DimensionFilters.resolve(ordinaryFirst, stack, SOURCE) == null,
                "native findFirst semantics do not skip a matching ordinary filter");
    }

    private static void registrySnapshot() {
        ItemStack stack = new ItemStack(FOREIGN);
        RegistryAccess original = registry(filter(item -> true, predicate(NETHER, 8)));
        RegistryAccess reloaded = registry(filter(item -> true, predicate(END, 1)));
        check(target(original, stack).equals(NETHER), "first registry snapshot resolves Nether");
        check(target(reloaded, stack).equals(END), "new snapshot with same stack resolves End");
        check(target(original, stack).equals(NETHER), "one snapshot does not overwrite another");
        RegistryAccess early = new RegistryAccess();
        check(DimensionFilters.resolve(early, stack, SOURCE) == null, "registry not yet ready uses fallback");
        early.filters = reloaded.filters;
        check(target(early, stack).equals(END), "failed early lookup is not cached");
        check(early.queries == 2, "registry candidate build retries after early failure");
    }

    private static void localFallback() throws Exception {
        ItemStack nether = DimensionFilters.create(NETHER);
        check(DimensionFilters.resolve(null, nether, SOURCE).dimension().equals(NETHER), "pre-registry vanilla route fallback");
        check(DimensionFilters.resolve(null, nether, SOURCE).coordinateScale() == 8, "fallback scale matches default JSON");
        check(DimensionFilters.resolve(null, nether, NETHER) == null, "same-dimension default has no remote route");
        Path file = FMLPaths.CONFIGDIR.directory.resolve("shuttershadow_dimensions.json");
        JsonObject config = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject routes = config.getAsJsonObject("filters").getAsJsonObject(ITEM.toString())
                .getAsJsonObject("targets").getAsJsonObject(NETHER.toString()).getAsJsonObject("routes");
        routes.getAsJsonObject(SOURCE.toString()).addProperty("coordinate_scale", 0);
        Files.writeString(file, config.toString());
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        check(DimensionFilters.resolve(null, nether, SOURCE) == null, "local config rejects the same invalid scale as data codec");
        routes.getAsJsonObject(SOURCE.toString()).remove("coordinate_scale");
        routes.getAsJsonObject(SOURCE.toString()).addProperty("target_dimension", "test:custom");
        Files.writeString(file, config.toString());
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 20_000));
        var route = DimensionFilters.resolve(null, nether, SOURCE);
        check(route.dimension().equals(id("test:custom")) && Double.isNaN(route.coordinateScale()),
                "local config shares optional-scale and custom-target codec semantics");
    }

    private static void resourcePaths(Path root) throws Exception {
        Packs empty = new Packs(); empty.put("base", "exposure:exposure/filter/red.json", "ordinary");
        Map<ResourceLocation, Resource> originals = EXPOSURE_FILES.listMatchingResources(empty);
        check(DimensionFilterResources.merge(empty, originals) == originals, "no dimension files leaves native map unchanged");
        Packs packs = new Packs();
        packs.put("base", "exposure:exposure/filter/red.json", "ordinary");
        packs.put("base", NEW_END.toString(), "dimension");
        packs.put("base", "shuttershadow:dimension_filter/test/moon/inner.json", "nested");
        packs.put("base", "other:dimension_filter/test/ignored.json", "foreign namespace");
        packs.put("base", "shuttershadow:dimension_filter/the_end.json", "missing dimension namespace");
        packs.put("base", "shuttershadow:dimension_filter/test/.json", "missing dimension ID");
        packs.put("base", "shuttershadow:dimension_filter/test/plain.txt", "not JSON");
        var merged = DimensionFilterResources.merge(packs, EXPOSURE_FILES.listMatchingResources(packs));
        check(merged.size() == 3, "only correctly namespaced JSON dimensions join standard Exposure resources");
        check(merged.get(VIRTUAL_END).content().equals("dimension"), "new dimension path maps into original Exposure registry");
        check(merged.get(id("shuttershadow:exposure/filter/test/moon/inner.json")).content().equals("nested"),
                "dimension IDs with slash retain the complete path");
        check(merged.get(id("exposure:exposure/filter/red.json")).content().equals("ordinary"), "ordinary native filter stays intact");
        check(originals.size() == 1, "merging does not mutate native input");
        for (String name : List.of("overworld", "the_nether", "the_end")) {
            Path json = root.resolve("src/main/resources/data/shuttershadow/dimension_filter/minecraft/" + name + ".json");
            JsonObject definition = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            JsonObject itemPredicate = definition.getAsJsonObject("predicate");
            check(itemPredicate.getAsJsonObject("components").get("shuttershadow:dimension_filter_target").getAsString()
                    .equals("minecraft:" + name), "migrated definition keeps target component: " + name);
            var predicate = DimensionCameraPredicate.CODEC.parse(JsonOps.INSTANCE,
                    itemPredicate.getAsJsonObject("predicates").get("shuttershadow:camera_dimension")).getOrThrow();
            check(predicate.routes().size() == 2, "migrated definition retains two cross-dimension routes: " + name);
            check(definition.get("shader").getAsString().equals("shuttershadow:shaders/post/neutral.json"),
                    "Exposure shader schema remains unchanged: " + name);
        }
    }

    private static void packPriorities() {
        Packs packs = new Packs();
        packs.put("base", NEW_END.toString(), "new default");
        packs.put("override", VIRTUAL_END.toString(), "native override");
        check(merge(packs).get(VIRTUAL_END).content().equals("native override"), "higher native pack overrides lower new directory");
        packs.put("highest", NEW_END.toString(), "new override");
        check(merge(packs).get(VIRTUAL_END).content().equals("new override"), "higher new-directory pack wins");
        Packs unaliased = new Packs();
        unaliased.put("base", NEW_END.toString(), "new default");
        unaliased.put("ordinary", LEGACY_END.toString(), "ordinary native file");
        var result = merge(unaliased);
        check(result.get(VIRTUAL_END).content().equals("new default"), "old vanilla filename is not an alias for new data ID");
        check(result.get(LEGACY_END).content().equals("ordinary native file"), "standard Exposure resources remain native");
        unaliased.put("latest-pack", NEW_END.toString(), "latest new");
        check(merge(unaliased).get(VIRTUAL_END).content().equals("latest new"), "resource reload reevaluates current pack order");
        Packs tied = new Packs();
        tied.put("one", VIRTUAL_END.toString(), "native same pack");
        tied.put("one", NEW_END.toString(), "new same pack");
        check(merge(tied).get(VIRTUAL_END).content().equals("new same pack"), "same pack prefers the declared new directory");
        Packs sorted = new Packs();
        sorted.put("base", "shuttershadow:exposure/filter/z_override.json", "native z");
        sorted.put("base", NEW_END.toString(), "dimension");
        sorted.put("base", "shuttershadow:exposure/filter/a_filter.json", "native a");
        List<ResourceLocation> ordered = new ArrayList<>(merge(sorted).keySet());
        check(ordered.equals(List.of(id("shuttershadow:exposure/filter/a_filter.json"), VIRTUAL_END,
                id("shuttershadow:exposure/filter/z_override.json"))), "mixed paths retain native TreeMap file order/findFirst semantics");
    }

    private static void networkFallback() {
        Packs packs = new Packs();
        packs.put("base", NEW_END.toString(), "new default");
        packs.put("user", VIRTUAL_END.toString(), "native override");
        check(DimensionFilterResources.fromNetwork(packs, VIRTUAL_END).content().equals("native override"),
                "known-pack ResourceManager reads the exact same layered winner as server");
        packs.put("higher", NEW_END.toString(), "new override");
        check(DimensionFilterResources.fromNetwork(packs, VIRTUAL_END).content().equals("new override"),
                "known-pack lookup follows new highest-priority resource");
        ResourceProvider onlyNew = file -> file.equals(NEW_END) ? Optional.of(new Resource("known", "new")) : Optional.empty();
        check(DimensionFilterResources.fromNetwork(onlyNew, VIRTUAL_END).content().equals("new"), "generic provider finds aliased dimension path");
        ResourceProvider onlyOld = file -> file.equals(LEGACY_END) ? Optional.of(new Resource("known", "old")) : Optional.empty();
        check(DimensionFilterResources.fromNetwork(onlyOld, VIRTUAL_END) == null, "old vanilla filenames are not network fallback aliases");
        check(DimensionFilterResources.fromNetwork(file -> Optional.empty(), VIRTUAL_END) == null, "missing resources defer to native error path");
        check(DimensionFilterResources.fromNetwork(packs, id("exposure:exposure/filter/red.json")) == null,
                "ordinary Exposure namespace never enters alias fallback");
        check(DimensionFilterResources.fromNetwork(packs, id("shuttershadow:exposure/filter/red.json")) == null,
                "ordinary native file without dimension path remains native");
    }

    private static void hookScope() throws Exception {
        Method scan = DimensionFilterDataMixin.class.getDeclaredMethod("shuttershadow$dimensionFiles",
                FileToIdConverter.class, ResourceManager.class, Operation.class, WritableRegistry.class);
        Method network = DimensionFilterDataMixin.class.getDeclaredMethod("shuttershadow$knownPackDimensionFile",
                ResourceProvider.class, ResourceLocation.class, Operation.class, WritableRegistry.class);
        scan.setAccessible(true); network.setAccessible(true);
        Packs packs = new Packs(); packs.put("base", NEW_END.toString(), "dimension");
        WritableRegistry<Filter> exposure = new WritableRegistry<>(Exposure.Registries.FILTER);
        WritableRegistry<Filter> other = new WritableRegistry<>(new ResourceKey<>(id("test:unrelated")));
        Map<ResourceLocation, Resource> untouched = Map.of(id("test:untouched.json"), new Resource("base", "ordinary"));
        int[] calls = {0};
        Operation<Map<ResourceLocation, Resource>> original = arguments -> {
            calls[0]++; check(arguments[0] == EXPOSURE_FILES && arguments[1] == packs, "original scan receives unchanged arguments");
            return untouched;
        };
        check(scan.invoke(null, EXPOSURE_FILES, packs, original, other) == untouched, "all other registries return exact native scan result");
        check(packs.scans == 0, "other registry hook performs no extra directory scan");
        @SuppressWarnings("unchecked") var merged = (Map<ResourceLocation, Resource>) scan.invoke(null, EXPOSURE_FILES, packs, original, exposure);
        check(merged.containsKey(VIRTUAL_END) && merged.size() == 2, "Exposure FILTER alone adds the dimension directory");
        check(calls[0] == 2, "original scanner executes exactly once per hook invocation");
        Resource nativeResource = new Resource("native", "sentinel");
        Operation<Resource> nativeRead = arguments -> { calls[0]++; return nativeResource; };
        int before = calls[0];
        check(network.invoke(null, packs, VIRTUAL_END, nativeRead, other) == nativeResource, "network alias is scoped to Exposure FILTER");
        check(calls[0] == before + 1, "other registries use original network read");
        before = calls[0];
        check(((Resource) network.invoke(null, packs, VIRTUAL_END, nativeRead, exposure)).content().equals("dimension"),
                "Exposure known-pack uses new directory");
        check(calls[0] == before, "successful alias does not read missing virtual file");
        check(network.invoke(null, packs, id("exposure:exposure/filter/red.json"), nativeRead, exposure) == nativeResource,
                "ordinary native filters continue through original network read");
    }

    private static Map<ResourceLocation, Resource> merge(Packs packs) {
        return DimensionFilterResources.merge(packs, EXPOSURE_FILES.listMatchingResources(packs));
    }

    private static RegistryAccess registry(Filter... filters) {
        RegistryAccess access = new RegistryAccess(); access.filters = new Registry<>(); access.filters.values.addAll(List.of(filters));
        return access;
    }

    private static DimensionCameraPredicate predicate(ResourceLocation target, double scale) {
        return new DimensionCameraPredicate(Map.of(SOURCE, new DimensionCameraPredicate.Route(target, scale)));
    }

    private static Filter filter(Predicate<ItemStack> matcher, DimensionCameraPredicate predicate) {
        return new Filter(new ItemPredicate(matcher, Map.of(new ItemSubPredicate.Type<>(), predicate)));
    }

    private static ResourceLocation target(RegistryAccess access, ItemStack stack) {
        return DimensionFilters.resolve(access, stack, SOURCE).dimension();
    }

    private static DimensionCameraPredicate parse(String json) {
        return DimensionCameraPredicate.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }

    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable work) {
        checks++;
        try { work.run(); } catch (Throwable error) { if (type.isInstance(error)) return; throw error; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    /** 模拟原生 ResourceManager：包由低到高，文件枚举按 ResourceLocation 排序。 */
    private static final class Packs implements ResourceManager {
        final Map<String, Map<ResourceLocation, Resource>> packs = new LinkedHashMap<>();
        int scans;
        void put(String pack, String file, String content) {
            packs.computeIfAbsent(pack, ignored -> new LinkedHashMap<>()).put(id(file), new Resource(pack, content));
        }
        public Optional<Resource> getResource(ResourceLocation file) {
            Resource result = null;
            for (var resources : packs.values()) if (resources.containsKey(file)) result = resources.get(file);
            return Optional.ofNullable(result);
        }
        public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> predicate) {
            scans++; Map<ResourceLocation, Resource> result = new TreeMap<>();
            for (var resources : packs.values()) resources.forEach((file, resource) -> {
                if (file.getPath().startsWith(path + "/") && predicate.test(file)) result.put(file, resource);
            });
            return result;
        }
        public Stream<PackResources> listPacks() { return packs.keySet().stream().map(PackResources::new); }
    }
}

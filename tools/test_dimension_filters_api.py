"""Run the production filter API, codecs, resolver and data-loader hooks without a game."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/xfw/shuttershadow"
FIXTURES = {
    "org.jetbrains.annotations.Nullable": "public @interface Nullable {}",
    "net.minecraft.resources.ResourceLocation": """public record ResourceLocation(String namespace, String path) implements Comparable<ResourceLocation> {
        public static final com.mojang.serialization.Codec<ResourceLocation> CODEC = com.mojang.serialization.Codec.STRING.comapFlatMap(
            value -> { var id = tryParse(value); return id == null ? com.mojang.serialization.DataResult.error(() -> "Invalid resource location") : com.mojang.serialization.DataResult.success(id); }, ResourceLocation::toString);
        public static ResourceLocation parse(String value) { var id = tryParse(value); if (id == null) throw new IllegalArgumentException(value); return id; }
        public static ResourceLocation tryParse(String value) { int split = value.indexOf(':'); return split < 0 ? tryBuild("minecraft", value) : tryBuild(value.substring(0, split), value.substring(split + 1)); }
        public static ResourceLocation tryBuild(String ns, String path) { return ns.matches("[a-z0-9_.-]+") && path.matches("[a-z0-9/._-]+") ? new ResourceLocation(ns, path) : null; }
        public static ResourceLocation fromNamespaceAndPath(String ns, String path) { return new ResourceLocation(ns, path); }
        public String getNamespace() { return namespace; } public String getPath() { return path; }
        public ResourceLocation withPath(String path) { return fromNamespaceAndPath(namespace, path); }
        public int compareTo(ResourceLocation other) { int order = path.compareTo(other.path); return order != 0 ? order : namespace.compareTo(other.namespace); }
        public String toString() { return namespace + ":" + path; }
    }""",
    "net.minecraft.resources.ResourceKey": "public record ResourceKey<T>(ResourceLocation location) {}",
    "net.minecraft.core.Registry": """public class Registry<T> {
        public java.util.List<T> values = new java.util.ArrayList<>();
        public java.util.stream.Stream<T> stream() { return values.stream(); }
    }""",
    "net.minecraft.core.WritableRegistry": """public class WritableRegistry<T> extends Registry<T> {
        private final net.minecraft.resources.ResourceKey<? extends Registry<T>> key;
        public WritableRegistry(net.minecraft.resources.ResourceKey<? extends Registry<T>> key) { this.key = key; }
        public net.minecraft.resources.ResourceKey<? extends Registry<T>> key() { return key; }
    }""",
    "net.minecraft.core.RegistryAccess": """public class RegistryAccess {
        public net.minecraft.core.Registry<io.github.mortuusars.exposure.data.Filter> filters;
        public int queries;
        @SuppressWarnings("unchecked") public <T> Registry<T> registryOrThrow(net.minecraft.resources.ResourceKey<? extends Registry<T>> key) {
            queries++; if (filters == null) throw new IllegalStateException("Registry not ready"); return (Registry<T>) filters;
        }
    }""",
    "net.minecraft.core.registries.BuiltInRegistries": """public class BuiltInRegistries {
        public static final Items ITEM = new Items(); public static class Items {
            public net.minecraft.resources.ResourceLocation getKey(net.minecraft.world.item.Item item) { return item.id; }
        }
    }""",
    "net.minecraft.core.registries.Registries": """public class Registries {
        public static String elementsDirPath(net.minecraft.resources.ResourceKey<?> registry) {
            return registry.location().getNamespace().equals("minecraft") ? registry.location().getPath() : registry.location().getNamespace() + "/" + registry.location().getPath();
        }
    }""",
    "net.minecraft.world.item.Item": """public class Item {
        public final net.minecraft.resources.ResourceLocation id;
        public Item(net.minecraft.resources.ResourceLocation id) { this.id = id; }
        public ItemStack getDefaultInstance() { return new ItemStack(this); }
    }""",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {
        public static final ItemStack EMPTY = new ItemStack(null);
        private final Item item; private final java.util.Map<Object, Object> components = new java.util.HashMap<>();
        public int count = 1, damage; public String customName = "";
        public ItemStack(Item item) { this.item = item; }
        public boolean isEmpty() { return item == null || count <= 0; } public Item getItem() { return item; }
        public boolean is(Item item) { return this.item == item && !isEmpty(); }
        @SuppressWarnings("unchecked") public <T> T get(Object key) { return (T) components.get(key); }
        public <T> void set(Object key, T value) { components.put(key, value); }
    }""",
    "net.minecraft.world.level.Level": """public class Level {
        public static final net.minecraft.resources.ResourceKey<Level> OVERWORLD = new net.minecraft.resources.ResourceKey<>(net.minecraft.resources.ResourceLocation.parse("minecraft:overworld"));
        private final net.minecraft.world.level.dimension.DimensionType type;
        public Level(double scale) { type = new net.minecraft.world.level.dimension.DimensionType(scale); }
        public net.minecraft.world.level.dimension.DimensionType dimensionType() { return type; }
    }""",
    "net.minecraft.world.level.dimension.DimensionType": """public record DimensionType(double coordinateScale) {
        public static double getTeleportationScale(DimensionType source, DimensionType target) { return source.coordinateScale / target.coordinateScale; }
    }""",
    "net.minecraft.world.phys.Vec3": """public class Vec3 {
        public final double x, y, z; public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        public Vec3 add(double x, double y, double z) { return new Vec3(this.x + x, this.y + y, this.z + z); }
    }""",
    "net.minecraft.advancements.critereon.ItemSubPredicate": """public interface ItemSubPredicate {
        boolean matches(net.minecraft.world.item.ItemStack stack); record Type<T extends ItemSubPredicate>() {}
    }""",
    "net.minecraft.advancements.critereon.ItemPredicate": """public record ItemPredicate(
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> matcher,
        java.util.Map<ItemSubPredicate.Type<?>, ItemSubPredicate> subPredicates) {
        public boolean test(net.minecraft.world.item.ItemStack stack) { return matcher.test(stack) && subPredicates.values().stream().allMatch(p -> p.matches(stack)); }
    }""",
    "io.github.mortuusars.exposure.data.Filter": "public record Filter(net.minecraft.advancements.critereon.ItemPredicate predicate) {}",
    "io.github.mortuusars.exposure.Exposure": """public class Exposure { public static class Registries {
        public static final net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<io.github.mortuusars.exposure.data.Filter>> FILTER = new net.minecraft.resources.ResourceKey<>(net.minecraft.resources.ResourceLocation.parse("exposure:filter"));
    }}""",
    "com.xfw.shuttershadow.Shuttershadow": """public class Shuttershadow {
        public static final String MODID = "shuttershadow";
        private static final net.minecraft.world.item.Item FILTER_ITEM = new net.minecraft.world.item.Item(net.minecraft.resources.ResourceLocation.parse("shuttershadow:dimension_filter"));
        public static final RegisteredItem DIMENSION_FILTER = new RegisteredItem();
        private static final Object TARGET = new Object();
        public static final java.util.function.Supplier<Object> DIMENSION_FILTER_TARGET = () -> TARGET;
        public static class RegisteredItem {
            public net.minecraft.world.item.Item get() { return FILTER_ITEM; }
            public net.minecraft.resources.ResourceLocation getId() { return FILTER_ITEM.id; }
        }
    }""",
    "net.neoforged.fml.loading.FMLPaths": """public class FMLPaths {
        public static final Config CONFIGDIR = new Config(); public static class Config {
            public java.nio.file.Path directory; public java.nio.file.Path get() { return directory; }
        }
    }""",
    "net.minecraft.server.packs.PackResources": "public record PackResources(String packId) {}",
    "net.minecraft.server.packs.resources.Resource": "public record Resource(String sourcePackId, String content) {}",
    "net.minecraft.server.packs.resources.ResourceProvider": """public interface ResourceProvider {
        java.util.Optional<Resource> getResource(net.minecraft.resources.ResourceLocation id);
        default Resource getResourceOrThrow(net.minecraft.resources.ResourceLocation id) { return getResource(id).orElseThrow(); }
    }""",
    "net.minecraft.server.packs.resources.ResourceManager": """public interface ResourceManager extends ResourceProvider {
        java.util.Map<net.minecraft.resources.ResourceLocation, Resource> listResources(String path, java.util.function.Predicate<net.minecraft.resources.ResourceLocation> predicate);
        java.util.stream.Stream<net.minecraft.server.packs.PackResources> listPacks();
    }""",
    "net.minecraft.resources.FileToIdConverter": """public class FileToIdConverter {
        private final String prefix; public FileToIdConverter(String prefix) { this.prefix = prefix; }
        public static FileToIdConverter json(String prefix) { return new FileToIdConverter(prefix); }
        public ResourceLocation idToFile(ResourceLocation id) { return id.withPath(prefix + "/" + id.getPath() + ".json"); }
        public ResourceLocation fileToId(ResourceLocation file) { return file.withPath(file.getPath().substring(prefix.length() + 1, file.getPath().length() - 5)); }
        public java.util.Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> listMatchingResources(net.minecraft.server.packs.resources.ResourceManager manager) {
            return manager.listResources(prefix, id -> id.getPath().endsWith(".json"));
        }
        public java.util.Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> listMatchingResourcesFromNamespace(net.minecraft.server.packs.resources.ResourceManager manager, String namespace) {
            return manager.listResources(prefix, id -> id.getNamespace().equals(namespace) && id.getPath().endsWith(".json"));
        }
    }""",
    "net.minecraft.resources.RegistryDataLoader": "public class RegistryDataLoader {}",
    "com.llamalad7.mixinextras.injector.wrapoperation.Operation": "public interface Operation<T> { T call(Object... args); }",
    "com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation": "public @interface WrapOperation { String method(); org.spongepowered.asm.mixin.injection.At at(); }",
    "com.llamalad7.mixinextras.sugar.Local": "public @interface Local { boolean argsOnly(); }",
    "org.spongepowered.asm.mixin.Mixin": "public @interface Mixin { Class<?> value(); }",
    "org.spongepowered.asm.mixin.injection.At": "public @interface At { String value(); String target(); }",
}


class DimensionFiltersApiTests(unittest.TestCase):
    def test_production_api_and_data_routes(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertIsNotNone(javac, "JDK 21 required")
        self.assertIsNotNone(java, "JDK 21 required")
        modules = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
        dependencies = []
        for module in ("com.mojang/datafixerupper", "com.google.code.gson/gson", "com.google.guava/guava", "it.unimi.dsi/fastutil"):
            jars = sorted((modules / module).rglob("*.jar"))
            jars = [p for p in jars if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
            self.assertTrue(jars, "Existing dependency missing: " + module)
            dependencies.append(str(jars[-1]))
        classpath = os.pathsep.join(dependencies)
        with tempfile.TemporaryDirectory(prefix="shuttershadow-filter-api-") as temporary:
            directory = Path(temporary)
            sources = []
            for name, body in FIXTURES.items():
                path = directory / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf-8")
                sources.append(str(path))
            sources += [str(JAVA / name) for name in (
                "api/DimensionFilters.java", "DimensionCameraPredicate.java", "DimensionCameraConfig.java",
                "DimensionFilterResources.java", "mixin/exposure/DimensionFilterDataMixin.java")]
            sources.append(str(ROOT / "tools/tests/DimensionFiltersApiTest.java"))
            compilation = subprocess.run([javac, "-J-Duser.language=en", "-encoding", "UTF-8", "--release", "21", "-proc:none",
                                          "-cp", classpath, "-d", str(directory), *sources], capture_output=True, text=True,
                                         encoding="utf-8", errors="replace")
            self.assertEqual(compilation.returncode, 0, compilation.stdout + compilation.stderr)
            for scenario in ("public-api", "predicate-codec", "stack-state", "registry-snapshot", "local-fallback",
                             "resource-paths", "pack-priorities", "network-fallback", "hook-scope"):
                with self.subTest(scenario=scenario):
                    execution = subprocess.run([java, "-Dfile.encoding=UTF-8", "-ea", "-cp", os.pathsep.join((str(directory), classpath)),
                                                "DimensionFiltersApiTest", scenario, str(directory), str(ROOT)], capture_output=True,
                                               text=True, encoding="utf-8", errors="replace")
                    self.assertEqual(execution.returncode, 0, execution.stdout + execution.stderr)
                    print(execution.stdout.strip(), flush=True)


if __name__ == "__main__":
    unittest.main()

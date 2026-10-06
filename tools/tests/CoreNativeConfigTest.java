package com.xfw.shuttershadow.core;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Real NeoForge/NightConfig defaults, file loading and reload checks; no game is started. */
public final class CoreNativeConfigTest {
    private static final String FILE_NAME = "shuttershadow-core.toml";
    private static final Map<String, Boolean> DEFAULTS = Map.ofEntries(
            Map.entry("enableClientPerformanceAdjustment", true),
            Map.entry("clientTolerantVersionMismatchWithServer", false),
            Map.entry("doCheckGlError", false),
            Map.entry("saveMemoryInBufferPack", false),
            Map.entry("enableWarning", true),
            Map.entry("serverSideNormalChunkLoading", true),
            Map.entry("chunkPacketDebug", false),
            Map.entry("enableRemoteChunkLoading", true),
            Map.entry("serverTolerantVersionMismatchWithClient", false),
            Map.entry("serverRejectClientWithoutShuttershadow", true),
            Map.entry("serverTeleportLogging", false));
    private static final Map<String, ModConfigSpec.BooleanValue> VALUES = new LinkedHashMap<>();
    private static ModConfigSpec.ConfigValue<List<? extends String>> disabledWarnings;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "runtime-");
        try {
            discoverValues();
            testDefaults();
            testListValidation();
            testNativeLifecycle(root);
            System.out.println("Native core configuration checks passed: " + checks);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void discoverValues() throws Exception {
        for (var field : CoreConfig.class.getFields()) {
            if (field.get(null) instanceof ModConfigSpec.BooleanValue value) {
                String name = value.getPath().getLast();
                VALUES.put(name, value);
            } else if (field.getName().equals("DISABLED_WARNINGS")) {
                disabledWarnings = (ModConfigSpec.ConfigValue<List<? extends String>>) field.get(null);
            }
        }
        check(VALUES.keySet().equals(DEFAULTS.keySet()), "all 11 existing booleans remain configurable");
        check(disabledWarnings != null, "disabled warnings remain configurable");
        for (String name : List.of("doCheckGlError", "activeLoading", "saveMemoryInBufferPack",
                "enableClientPerformanceAdjustment", "chunkPacketDebug")) {
            check(Modifier.isVolatile(CoreSettings.class.getField(name).getModifiers()),
                    name + " publishes configuration reloads across threads");
        }
        check(!CoreSettings.doCheckGlError && !CoreSettings.saveMemoryInBufferPack,
                "pre-loading renderer defaults match the previous JSON defaults");
    }

    private static void testDefaults() {
        CommentedConfig config = CommentedConfig.of(TomlFormat.instance());
        CoreConfig.SPEC.correct(config);
        assertBooleans(config, false, "defaults");
        check(((List<?>) config.get(disabledWarnings.getPath())).isEmpty(), "default warning exclusions are empty");
        check(CoreConfig.SPEC.isCorrect(config), "NeoForge validates the generated defaults");
        boolean rejected = false;
        try {
            VALUES.get("enableWarning").get();
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        check(rejected, "real NeoForge values are not read during construction before loading");
    }

    private static void testListValidation() {
        CommentedConfig config = CommentedConfig.of(TomlFormat.instance());
        CoreConfig.SPEC.correct(config);
        config.set(disabledWarnings.getPath(), List.of("iris", 7, "low_max_memory"));
        check(!CoreConfig.SPEC.isCorrect(config), "native validation rejects non-string warning IDs");
        CoreConfig.SPEC.correct(config);
        check(config.get(disabledWarnings.getPath()).equals(List.of("iris", "low_max_memory")),
                "native correction retains valid warning IDs");
        config.set(disabledWarnings.getPath(), List.of());
        check(CoreConfig.SPEC.isCorrect(config), "empty warning exclusion lists are valid");
        config.set(disabledWarnings.getPath(), "iris");
        CoreConfig.SPEC.correct(config);
        check(((List<?>) config.get(disabledWarnings.getPath())).isEmpty(), "invalid list values become the native empty default");
    }

    private static void testNativeLifecycle(Path root) throws Exception {
        Path game = directory(root, "native-lifecycle");
        FMLPaths.loadAbsolutePaths(game);
        Path oldJson = FMLPaths.CONFIGDIR.get().resolve("shuttershadow-core.json");
        String oldContents = "{\"enableWarning\":false,\"enableImmPtlChunkLoading\":false}";
        Files.writeString(oldJson, oldContents);
        TestContainer container = new TestContainer();
        int[] loading = {0}, reloading = {0};
        container.bus.addListener(ModConfigEvent.Loading.class, event -> loading[0]++);
        container.bus.addListener(ModConfigEvent.Reloading.class, event -> reloading[0]++);
        CoreConfig.register(container, container.bus);
        ModConfig core = ModConfigs.getModConfigs("shuttershadow").stream()
                .filter(config -> config.getSpec() == CoreConfig.SPEC).findFirst().orElseThrow();
        check(core.getType() == ModConfig.Type.COMMON, "core settings remain local global COMMON configuration");
        check(core.getFileName().equals(FILE_NAME), "the native config uses the explicit core TOML filename");
        try {
            ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get());
            check(loading[0] == 1, "native initial file load fires Loading");
            assertRuntime(false, "initial native load");
            check(Files.readString(oldJson).equals(oldContents), "obsolete JSON files are ignored and never rewritten");
            DEFAULTS.forEach((name, value) -> VALUES.get(name).set(!value));
            disabledWarnings.set(List.of("iris"));
            CoreConfig.SPEC.save();
            check(reloading[0] >= 1, "native UI-style save fires Reloading");
            assertRuntime(true, "native save reload");
            CommentedConfig persisted = read(core.getFullPath());
            assertBooleans(persisted, true, "native TOML save");
            check(persisted.get(disabledWarnings.getPath()).equals(List.of("iris")), "native save persists edited warning IDs");
            testWarningPolicy();
            DEFAULTS.forEach((name, value) -> VALUES.get(name).set(!value));
            CoreConfig.SPEC.save();

            ModConfigSpec otherSpec = new ModConfigSpec.Builder().define("unrelated", true).next().build();
            ModConfig other = ConfigTracker.INSTANCE.registerConfig(ModConfig.Type.COMMON, otherSpec, container, "unrelated.toml");
            CoreSettings.chunkPacketDebug = false;
            container.bus.post(new ModConfigEvent.Loading(other));
            container.bus.post(new ModConfigEvent.Reloading(other));
            check(!CoreSettings.chunkPacketDebug, "another registered spec cannot apply the core caches");

            // Reproduce the real loader reload, rather than substituting an in-memory config implementation.
            var load = ConfigTracker.class.getDeclaredMethod("loadConfig", ModConfig.class, Path.class, Function.class);
            load.setAccessible(true);
            load.invoke(null, core, core.getFullPath(), (Function<ModConfig, ModConfigEvent>) ModConfigEvent.Reloading::new);
            assertRuntime(true, "persisted native reload");
            check(VALUES.get("serverRejectClientWithoutShuttershadow").get() == false,
                    "server and client independent options remain readable after reload");
        } finally {
            ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.COMMON);
        }
    }

    private static void testWarningPolicy() {
        VALUES.get("enableWarning").set(true);
        disabledWarnings.set(List.of("iris"));
        check(!CoreConfig.shouldDisplayWarning("iris"), "a configured warning ID is suppressed");
        check(CoreConfig.shouldDisplayWarning("low_max_memory"), "other warning IDs remain visible");
        disabledWarnings.set(List.of());
        check(CoreConfig.shouldDisplayWarning("iris"), "clearing the exclusion list re-enables the warning");
        VALUES.get("enableWarning").set(false);
        check(!CoreConfig.shouldDisplayWarning("iris") && !CoreConfig.shouldDisplayWarning("low_max_memory"),
                "the global warning switch suppresses every warning");
    }

    private static void assertRuntime(boolean inverted, String context) {
        DEFAULTS.forEach((name, value) -> check(VALUES.get(name).get() == (inverted != value), context + ": " + name));
        check(CoreSettings.doCheckGlError == inverted, context + ": GL checking cache");
        check(CoreSettings.saveMemoryInBufferPack == inverted, context + ": buffer allocation cache");
        check(CoreSettings.activeLoading == !inverted, context + ": chunk activity cache");
        check(CoreSettings.enableClientPerformanceAdjustment == !inverted, context + ": client performance cache");
        check(CoreSettings.chunkPacketDebug == inverted, context + ": chunk packet logging cache");
    }

    private static void assertBooleans(CommentedConfig config, boolean inverted, String context) {
        DEFAULTS.forEach((name, value) -> check(bool(config, name) == (inverted != value), context + ": " + name));
    }

    private static boolean bool(CommentedConfig config, String name) {
        return config.get(VALUES.get(name).getPath());
    }

    private static Path directory(Path root, String name) throws Exception {
        return Files.createDirectory(root.resolve(name));
    }

    private static CommentedConfig read(Path path) throws Exception {
        CommentedConfig config = CommentedConfig.of(TomlFormat.instance());
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            new TomlParser().parse(reader, config, ParsingMode.REPLACE);
        }
        return config;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestContainer extends ModContainer {
        private final IEventBus bus = BusBuilder.builder().allowPerPhasePost().markerType(IModBusEvent.class).build();

        private TestContainer() {
            super((IModInfo) Proxy.newProxyInstance(IModInfo.class.getClassLoader(), new Class<?>[]{IModInfo.class},
                    (proxy, method, args) -> method.getName().equals("getModId") ? "shuttershadow" : null));
        }

        @Override
        public IEventBus getEventBus() { return bus; }
    }
}

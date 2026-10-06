import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 原样执行生产生命周期方法，替换 Minecraft/GL 环境并注入不同阶段的异常。 */
public final class ClientWorldLifecycleTest {
    private static int assertions;
    private static int scenarios;
    private static final ResourceKey<Level> SOURCE = new ResourceKey<>("source");
    private static final ResourceKey<Level> TARGET = new ResourceKey<>("target");
    private static final ResourceKey<DimensionType> TYPE = new ResourceKey<>("type");

    public static void main(String[] args) {
        constructorFailure(false);
        constructorFailure(true);
        for (String stage : List.of("mapping", "world", "setLevel", "resources")) creationFailure(stage);
        suppressedCleanup();
        successfulCreation();
        failedReloadAndRetry();
        missingReloadWorld();
        System.out.println("PASS: client world lifecycle: " + scenarios + " scenarios, " + assertions + " assertions");
    }

    private static void reset() {
        scenarios++;
        Failures.stage = "";
        Failures.error = false;
        Failures.cleanup = false;
        Failures.last = null;
        LevelRenderer.created.clear();
        Minecraft client = ClientWorldLoader.CLIENT;
        client.profiler.depth = 0;
        client.level = new ClientLevel(SOURCE);
        client.levelRenderer = new LevelRenderer();
        client.entities.setLevel(client.level);
        client.entities.camera = client.originalCamera;
        client.particleEngine.world = client.level;
        client.player = new Player(new ClientPacketListener(client.level));
        ClientWorldLoader.CLIENT_WORLD_MAP.clear();
        ClientWorldLoader.WORLD_RENDERER_MAP.clear();
        ClientWorldLoader.CLIENT_WORLD_MAP.put(SOURCE, client.level);
        ClientWorldLoader.WORLD_RENDERER_MAP.put(SOURCE, client.levelRenderer);
        ClientWorldLoader.dimIdToDimTypeId = new HashMap<>(Map.of(TARGET, TYPE));
    }

    private static Throwable failure(Runnable action) {
        try { action.run(); }
        catch (Throwable thrown) { return thrown; }
        throw new AssertionError("expected injected failure");
    }

    private static void recovered() {
        check(!ClientWorldLoader.getIsCreatingClientWorld(), "creation flag must be restored");
        check(ClientWorldLoader.CLIENT.profiler.depth == 0, "profiler stack must be restored");
        check(!ClientWorldLoader.CLIENT_WORLD_MAP.containsKey(TARGET), "failed world must not be cached");
        check(!ClientWorldLoader.WORLD_RENDERER_MAP.containsKey(TARGET), "failed renderer must not be cached");
        check(ClientWorldLoader.CLIENT.entities.world == ClientWorldLoader.CLIENT.level,
                "failed disposal must recover the shared entity renderer world");
        check(ClientWorldLoader.CLIENT.entities.camera == ClientWorldLoader.CLIENT.originalCamera,
                "failed disposal must recover the shared entity renderer camera");
    }

    private static void constructorFailure(boolean error) {
        reset(); Failures.stage = "constructor"; Failures.error = error;
        Throwable thrown = failure(() -> ClientWorldLoader.createForTest(TARGET));
        recovered();
        check(error ? thrown == Failures.last : thrown.getCause() == Failures.last,
                "constructor failure must remain available with original Error semantics");
        check(LevelRenderer.created.isEmpty(), "failed constructor supplies no disposable renderer");
    }

    private static void creationFailure(String stage) {
        reset(); Failures.stage = stage;
        if (stage.equals("mapping")) ClientWorldLoader.dimIdToDimTypeId.clear();
        Throwable thrown = failure(() -> ClientWorldLoader.createForTest(TARGET));
        check(thrown instanceof IllegalStateException, "creation exceptions need dimension context");
        check(stage.equals("mapping") || thrown.getCause() == Failures.last, "original creation failure must be retained");
        recovered();
        LevelRenderer renderer = LevelRenderer.created.getFirst();
        check(renderer.detaches == 1 && renderer.closes == 1 && renderer.disposes == 1,
                "unregistered renderer must detach and release all owned resources");
        Failures.stage = "";
        ClientWorldLoader.dimIdToDimTypeId.put(TARGET, TYPE);
        ClientLevel retried = ClientWorldLoader.createForTest(TARGET);
        check(retried == ClientWorldLoader.CLIENT_WORLD_MAP.get(TARGET), "later creation must recover normally");
    }

    private static void suppressedCleanup() {
        reset(); Failures.stage = "resources"; Failures.cleanup = true;
        Throwable thrown = failure(() -> ClientWorldLoader.createForTest(TARGET));
        check(thrown.getCause() == Failures.last, "cleanup exception must not replace creation failure");
        check(thrown.getCause().getSuppressed().length == 1, "cleanup failure must remain as suppressed evidence");
        check(thrown.getCause().getSuppressed()[0].getMessage().equals("cleanup"), "suppressed error must identify disposal");
        recovered();
    }

    private static void successfulCreation() {
        reset();
        ClientLevel world = ClientWorldLoader.createForTest(TARGET);
        LevelRenderer renderer = LevelRenderer.created.getFirst();
        check(ClientWorldLoader.CLIENT_WORLD_MAP.get(TARGET) == world, "success must cache created world");
        check(ClientWorldLoader.WORLD_RENDERER_MAP.get(TARGET) == renderer, "success must cache its renderer");
        check(renderer.world == world, "renderer must bind the newly created world");
        check(renderer.closes == 0 && renderer.disposes == 0 && renderer.detaches == 0, "success must retain live resources");
        check(world.mapData == ClientWorldLoader.CLIENT.level.mapData, "map sharing must remain unchanged");
        check(world.tickRateManager == ClientWorldLoader.CLIENT.level.tickRateManager, "tick-rate sharing must remain unchanged");
        check(!ClientWorldLoader.getIsCreatingClientWorld() && ClientWorldLoader.CLIENT.profiler.depth == 0,
                "successful creation must restore lifecycle state");
    }

    private static void failedReloadAndRetry() {
        reset();
        ClientLevel remote = ClientWorldLoader.createForTest(TARGET);
        Minecraft client = ClientWorldLoader.CLIENT;
        ClientLevel sourceWorld = client.level;
        LevelRenderer sourceRenderer = client.levelRenderer;
        LevelRenderer remoteRenderer = ClientWorldLoader.WORLD_RENDERER_MAP.get(TARGET);
        Failures.stage = "reload";
        Throwable thrown = failure(ClientWorldLoader::_onWorldRendererReloaded);
        check(thrown == Failures.last, "reload must retain its original exception");
        check(!ClientWorldLoader.reloadingForTest(), "failed reload must clear recursion guard");
        check(client.level == sourceWorld && client.levelRenderer == sourceRenderer, "reload must recover active world and renderer");
        check(client.particleEngine.world == sourceWorld && client.player.connection.world == sourceWorld,
                "reload must recover particle and packet contexts");
        Failures.stage = "";
        ClientWorldLoader._onWorldRendererReloaded();
        check(remoteRenderer.reloads == 2, "later reload must run instead of staying blocked");
        check(remoteRenderer.world == remote && !ClientWorldLoader.reloadingForTest(), "retry must retain remote renderer binding");
    }

    private static void missingReloadWorld() {
        reset();
        ClientWorldLoader.WORLD_RENDERER_MAP.put(TARGET, new LevelRenderer());
        Throwable thrown = failure(ClientWorldLoader::_onWorldRendererReloaded);
        check(thrown instanceof NullPointerException, "missing world must preserve validation failure");
        check(!ClientWorldLoader.reloadingForTest(), "validation failure must also clear reload recursion guard");
    }

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }
}

final class Failures {
    static String stage = "";
    static boolean error, cleanup;
    static Throwable last;
    static void inject(String point) {
        if (!stage.equals(point)) return;
        last = error ? new AssertionError(point) : new IllegalArgumentException(point);
        if (last instanceof Error fatal) throw fatal;
        throw (RuntimeException) last;
    }
}

record ResourceKey<T>(String location) {}
final class DimensionType {}
record Holder<T>(T value) {}
record MapId(int id) {}
final class MapItemSavedData {}
final class Registries { static final Object DIMENSION_TYPE = new Object(); }
final class RegistryAccess {
    Registry registryOrThrow(Object key) { return new Registry(); }
    static final class Registry {
        Holder<DimensionType> getHolderOrThrow(ResourceKey<DimensionType> key) { return new Holder<>(new DimensionType()); }
    }
}
class Level {
    final ResourceKey<Level> key;
    Level(ResourceKey<Level> key) { this.key = key; }
    ResourceKey<Level> dimension() { return key; }
}
final class ClientLevel extends Level implements IEClientLevel_Accessor, IEClientWorld {
    Map<MapId, MapItemSavedData> mapData = new HashMap<>();
    Object tickRateManager = new Object();
    final BiomeManager biome = new BiomeManager();
    final ClientLevelData properties = new ClientLevelData(new Object(), false, false);
    ClientLevel(ResourceKey<Level> key) { super(key); }
    ClientLevel(ClientPacketListener handler, ClientLevelData data, ResourceKey<Level> dimension,
                Holder<DimensionType> type, int loadDistance, int simulationDistance, Supplier<?> profiler,
                LevelRenderer renderer, boolean debug, long biomeSeed) {
        super(dimension); Failures.inject("world");
    }
    int getServerSimulationDistance() { return 3; }
    boolean isDebug() { return false; }
    BiomeManager getBiomeManager() { return biome; }
    Object tickRateManager() { return tickRateManager; }
    public ClientLevelData getLevelData() { return properties; }
    public Map<MapId, MapItemSavedData> ip_getMapData() { return mapData; }
    public void ip_setMapData(Map<MapId, MapItemSavedData> data) { mapData = data; }
    public void ip_setTickRateManager(Object manager) { tickRateManager = manager; }
    static final class BiomeManager { long biomeZoomSeed; }
    static final class ClientLevelData implements IEClientLevelData {
        final Object difficulty; final boolean hardcore, flat;
        ClientLevelData(Object difficulty, boolean hardcore, boolean flat) {
            this.difficulty = difficulty; this.hardcore = hardcore; this.flat = flat;
        }
        Object getDifficulty() { return difficulty; }
        boolean isHardcore() { return hardcore; }
        public boolean ip_getIsFlat() { return flat; }
    }
}
final class LevelRenderer implements IEWorldRenderer {
    static final List<LevelRenderer> created = new ArrayList<>();
    int closes, disposes, detaches, reloads;
    ClientLevel world;
    final EntityRenderDispatcher entities;
    LevelRenderer() { entities = ClientWorldLoader.CLIENT.entities; }
    LevelRenderer(Minecraft client, Object entities, Object blocks, Object buffers) {
        this.entities = (EntityRenderDispatcher) entities;
        Failures.inject("constructor"); created.add(this);
    }
    void setLevel(ClientLevel value) {
        entities.setLevel(value);
        world = value;
        if (value != null) Failures.inject("setLevel");
        else {
            detaches++;
            if (Failures.cleanup) throw new IllegalStateException("cleanup");
        }
    }
    void onResourceManagerReload(Object resources) { Failures.inject("resources"); }
    void close() { closes++; }
    public void portal_fullyDispose() { disposes++; }
    void allChanged() {
        reloads++; Failures.inject("reload"); ClientWorldLoader._onWorldRendererReloaded();
    }
}
final class ClientPacketListener implements IEClientPlayNetworkHandler {
    ClientLevel world;
    ClientPacketListener(ClientLevel world) { this.world = world; }
    RegistryAccess registryAccess() { return new RegistryAccess(); }
    ClientLevel getLevel() { return world; }
    Set<ResourceKey<Level>> levels() { return Set.of(new ResourceKey<>("source"), new ResourceKey<>("target")); }
    public void ip_setWorld(ClientLevel world) { this.world = world; }
}
final class Player { final ClientPacketListener connection; Player(ClientPacketListener connection) { this.connection = connection; } }
final class ParticleEngine implements IEParticleManager {
    ClientLevel world;
    public void ip_setWorld(ClientLevel world) { this.world = world; }
}
final class Minecraft implements IEMinecraftClient {
    ClientLevel level;
    LevelRenderer levelRenderer;
    Player player;
    final ParticleEngine particleEngine = new ParticleEngine();
    final EntityRenderDispatcher entities = new EntityRenderDispatcher();
    final Camera originalCamera = new Camera();
    final Profiler profiler = new Profiler();
    boolean isSameThread() { return true; }
    Profiler getProfiler() { return profiler; }
    EntityRenderDispatcher getEntityRenderDispatcher() { return entities; }
    Object getBlockEntityRenderDispatcher() { return new Object(); }
    Object renderBuffers() { return new Object(); }
    Object getResourceManager() { return new Object(); }
    ClientPacketListener getConnection() { return player.connection; }
    public void ip_setWorldRenderer(LevelRenderer renderer) { levelRenderer = renderer; }
    static final class Profiler {
        int depth;
        void push(String name) { depth++; }
        void pop() { if (--depth < 0) throw new AssertionError("profiler underflow"); }
    }
}
final class Camera {}
final class EntityRenderDispatcher {
    ClientLevel world;
    Camera camera;
    void setLevel(ClientLevel world) { this.world = world; if (world == null) camera = null; }
}
interface IEClientLevel_Accessor {
    Map<MapId, MapItemSavedData> ip_getMapData();
    void ip_setMapData(Map<MapId, MapItemSavedData> value);
}
interface IEClientWorld { void ip_setTickRateManager(Object value); }
interface IEClientLevelData { boolean ip_getIsFlat(); }
interface IEWorldRenderer { void portal_fullyDispose(); }
interface IEMinecraftClient { void ip_setWorldRenderer(LevelRenderer renderer); }
interface IEParticleManager { void ip_setWorld(ClientLevel world); }
interface IEClientPlayNetworkHandler { void ip_setWorld(ClientLevel world); }
final class TestLogger {
    void info(String message, Object... values) {}
    void error(String message, Object... values) {}
}
final class Validate {
    static void isTrue(boolean condition, String... message) { if (!condition) throw new IllegalArgumentException(); }
    static <T> T notNull(T value, String message, Object... arguments) { if (value == null) throw new NullPointerException(message); return value; }
}

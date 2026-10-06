import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 执行生产雾上下文及实际注册的退出监听；原生雾字段以可读写记录替代。 */
public final class FogContextLifecycleTest {
    private static final ResourceKey<Level> SOURCE = new ResourceKey<>("source");
    private static final ResourceKey<Level> TARGET = new ResourceKey<>("target");
    private static final ResourceKey<Level> NEW_SERVER = new ResourceKey<>("new_server");
    private static final FogRendererContext nativeFog = new FogRendererContext();
    private static int assertions;

    public static void main(String[] args) {
        FogRendererContext.copyContextFromObject = context -> copy(context, nativeFog);
        FogRendererContext.copyContextToObject = context -> copy(nativeFog, context);
        FogRendererContext.init();
        RenderStates.originalPlayerDimension = SOURCE;
        ClientWorldLoader.worlds.add(new Level(SOURCE));
        ClientWorldLoader.worlds.add(new Level(TARGET));
        FogRendererContext.update();
        var manager = FogRendererContext.swappingManager;
        check(manager.contextMap.size() == 2, "first update must create each active dimension context");

        nativeFog.red = 0.4F; nativeFog.targetBiomeFog = 100;
        manager.pushSwapping(TARGET);
        check(nativeFog.targetBiomeFog == -1, "first remote view must start with an independent fog transition");
        nativeFog.red = 0.8F; nativeFog.targetBiomeFog = 200;
        manager.popSwapping();
        check(nativeFog.red == 0.4F && nativeFog.targetBiomeFog == 100, "remote view must restore source fog fields");
        check(manager.contextMap.get(TARGET).context.targetBiomeFog == 200, "remote fog history must remain cached during a connection");

        FogRendererContext.onPlayerTeleport(SOURCE, TARGET);
        check(nativeFog.red == 0.8F && nativeFog.targetBiomeFog == 200, "seamless teleport must apply cached target context");
        check(manager.getCurrentDimension() == TARGET && manager.contextMap.size() == 2,
                "seamless teleport must retain dimension records");
        NeoForge.EVENT_BUS.post(new ClientCleanupEvent());
        check(manager.contextMap.size() == 2, "ordinary world cleanup must not clear connection fog records");

        nativeFog.previousBiomeFog = 199; nativeFog.biomeChangedTime = 12345;
        NeoForge.EVENT_BUS.post(new ClientPlayerNetworkEvent.LoggingOut());
        check(manager.contextMap.isEmpty(), "real connection logout must release all cached dimension records");
        check(nativeFog.red == 0.8F && nativeFog.targetBiomeFog == 200
                && nativeFog.previousBiomeFog == 199 && nativeFog.biomeChangedTime == 12345,
                "logout must leave vanilla fog fields untouched");
        check(!manager.isSwapped(), "logout between frames must leave an empty render-swap stack");

        ClientWorldLoader.worlds.clear();
        ClientWorldLoader.worlds.add(new Level(NEW_SERVER));
        ClientWorldLoader.worlds.add(new Level(TARGET));
        RenderStates.originalPlayerDimension = NEW_SERVER;
        FogRendererContext.update();
        check(manager.getCurrentDimension() == NEW_SERVER, "next update must replace previous outer dimension");
        check(manager.contextMap.size() == 2 && !manager.contextMap.containsKey(SOURCE), "new connection must only rebuild its own records");
        check(manager.contextMap.get(TARGET).context.targetBiomeFog == -1,
                "reused dimension key must not inherit previous server's underwater biome transition");
        manager.pushSwapping(TARGET);
        check(nativeFog.targetBiomeFog == -1 && nativeFog.previousBiomeFog == -1,
                "first new-server remote view must use fresh fog transition state");
        manager.popSwapping();
        check(nativeFog.targetBiomeFog == 200 && nativeFog.previousBiomeFog == 199,
                "new remote view must still restore vanilla main-world fields");
        NeoForge.EVENT_BUS.post(new ClientPlayerNetworkEvent.LoggingOut());
        NeoForge.EVENT_BUS.post(new ClientPlayerNetworkEvent.LoggingOut());
        check(manager.contextMap.isEmpty(), "repeated logout including server creation must be harmless");
        System.out.println("PASS: fog connection lifecycle: " + assertions + " assertions");
    }

    private static void copy(FogRendererContext source, FogRendererContext target) {
        target.red = source.red; target.green = source.green; target.blue = source.blue;
        target.targetBiomeFog = source.targetBiomeFog;
        target.previousBiomeFog = source.previousBiomeFog;
        target.biomeChangedTime = source.biomeChangedTime;
    }

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }
}

record ResourceKey<T>(String name) {}
record Vec3(double x, double y, double z) {}
record Level(ResourceKey<Level> dimension) {}
final class FogRenderer {}
final class RenderStates { static ResourceKey<Level> originalPlayerDimension; }
final class ClientWorldLoader {
    static final List<Level> worlds = new ArrayList<>();
    static boolean getIsInitialized() { return true; }
    static List<Level> getClientWorlds() { return worlds; }
}
final class ClientPlayerNetworkEvent { static final class LoggingOut {} }
final class ClientCleanupEvent {}
final class NeoForge {
    static final EventBus EVENT_BUS = new EventBus();
    static final class EventBus {
        private final Map<Class<?>, List<Consumer<?>>> listeners = new HashMap<>();
        <T> void addListener(Class<T> type, Consumer<T> action) {
            listeners.computeIfAbsent(type, key -> new ArrayList<>()).add(action);
        }
        @SuppressWarnings("unchecked")
        void post(Object event) {
            for (Consumer<?> listener : listeners.getOrDefault(event.getClass(), List.of())) {
                ((Consumer<Object>) listener).accept(event);
            }
        }
    }
}
final class Validate {
    static void isTrue(boolean condition) { if (!condition) throw new IllegalArgumentException(); }
    static <T> T notNull(T value) { if (value == null) throw new NullPointerException(); return value; }
}

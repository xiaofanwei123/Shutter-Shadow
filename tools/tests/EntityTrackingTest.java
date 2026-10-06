import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** 执行生产实体订阅代码，只替换世界和网络环境，不启动游戏。 */
public final class EntityTrackingTest {
    private static final String SOURCE = "com/xfw/shuttershadow/mixin/minecraft/server/MixinTrackedEntity";
    private static final String FIXTURE = "EntityTrackingFixture";
    private static final String TEST = "EntityTrackingTest";
    private static final Map<String, Object2ObjectOpenHashMap<Player, Watch>> RECORDS = new HashMap<>();
    private static int checks;
    private static int nativeUpdates;

    public static void main(String[] args) throws Exception {
        ClassNode source = read(Path.of(args[0]), SOURCE);
        check(source.methods.stream().noneMatch(m -> m.name.equals("updatePlayers")), "vanilla updatePlayers must remain intact");
        ClassNode chunkMixin = read(Path.of(args[0]), "com/xfw/shuttershadow/mixin/minecraft/server/MixinChunkMap_C");
        check(chunkMixin.methods.stream().noneMatch(m -> m.name.equals("onTickEntityMovement") || m.name.equals("redirectUpdatePlayers")),
                "vanilla chunk tick and initial local pairing must not be cancelled");
        ClassNode entitySync = read(Path.of(args[0]), "com/xfw/shuttershadow/core/chunk_loading/EntitySync");
        check(entitySync.methods.stream().noneMatch(m -> m.name.equals("tick") || calls(m, "sendChanges")),
                "remote subscription refresh must not send a second entity tick");
        nativeVisibilityAndVehicleTargets(Path.of(args[0]), Path.of(args[1]), source);
        Class<?> fixture = fixture(source);
        nativeOnly(fixture);
        remote(fixture);
        sourceExtra(fixture);
        seamlessTransfer(fixture);
        hiddenEntities(fixture);
        vehicleObserverCleanup(fixture);
        System.out.println("Entity tracking production fixture passed " + checks + " checks.");
    }

    private static void nativeVisibilityAndVehicleTargets(Path compiled, Path nativeJar, ClassNode source) throws Exception {
        MethodNode predicate = method(source, "shuttershadow$watchesAdditionalEntity");
        check(callCount(predicate, "broadcastToPlayer") == 1,
                "camera visibility must evaluate the native entity predicate exactly once");
        try (ZipFile jar = new ZipFile(nativeJar.toFile())) {
            ClassNode nativeTracker = new ClassNode();
            new ClassReader(jar.getInputStream(jar.getEntry("net/minecraft/server/level/ChunkMap$TrackedEntity.class")).readAllBytes())
                    .accept(nativeTracker, 0);
            check(callCount(method(nativeTracker, "updatePlayer"), "broadcastToPlayer") == 1,
                    "camera subscription must reuse the actual native tracking visibility predicate");
            ClassNode nativePlayer = new ClassNode();
            new ClassReader(jar.getInputStream(jar.getEntry("net/minecraft/server/level/ServerPlayer.class")).readAllBytes())
                    .accept(nativePlayer, 0);
            MethodNode playerVisibility = method(nativePlayer, "broadcastToPlayer");
            check(callCount(playerVisibility, "isSpectator") == 2 && callCount(playerVisibility, "getCamera") == 1,
                    "actual NeoForge player visibility must gate spectator state and spectator camera");
            ClassNode nativeEntry = new ClassNode();
            new ClassReader(jar.getInputStream(jar.getEntry("net/minecraft/server/level/ServerEntity.class")).readAllBytes())
                    .accept(nativeEntry, 0);
            MethodNode removePairing = method(nativeEntry, "removePairing");
            check(callCount(removePairing, "stopSeenByPlayer") == 1 && callCount(removePairing, "onStopEntityTracking") == 1,
                    "observer cleanup must retain both native and NeoForge tracking callbacks");
        }

        ClassNode manager = read(compiled, "com/xfw/shuttershadow/core/teleportation/ServerTeleportationManager");
        MethodNode transfer = method(manager, "teleportVehicleAcrossDimensions");
        check((transfer.access & Opcodes.ACC_PRIVATE) != 0,
                "vehicle implementation must remain internal to the validated player transfer path");
        check(transfer.desc.equals("(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/server/level/ServerLevel;"
                        + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/world/entity/Entity;"),
                "vehicle helper must reuse the validated target world and receive the exact rider");
        int cleanupPosition = -1;
        int removalPosition = -1;
        int index = 0;
        for (AbstractInsnNode instruction : transfer.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("ip_stopTrackingExcept")) {
                    cleanupPosition = index;
                    AbstractInsnNode argument = call.getPrevious();
                    while (argument != null && argument.getOpcode() < 0) argument = argument.getPrevious();
                    check(argument instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 4,
                            "only the exact transferring rider may retain its client vehicle");
                }
                if (call.owner.equals("net/minecraft/world/entity/Entity") && call.name.equals("remove")) removalPosition = index;
            }
            index++;
        }
        check(cleanupPosition >= 0 && cleanupPosition < removalPosition,
                "source observers must be unpaired before the old vehicle disappears from entityMap");
        check(callCount(transfer, "ip_stopTrackingExcept") == 1 && callCount(transfer, "restoreFrom") == 1,
                "vehicle transfer must clean observers once while preserving serialized vehicle state");
        check(callCount(transfer, "getServer") == 0,
                "vehicle helper must not re-resolve the server after target validation");
        ClassNode serverEntryMixin = read(compiled, "com/xfw/shuttershadow/mixin/minecraft/server/MixinServerEntity");
        MethodNode packetRoute = method(serverEntryMixin, "shuttershadow$sendEntityPairingPacket");
        check(callCount(packetRoute, "sendRedirectedPacket") == 1 && callCount(packetRoute, "dimension") == 1,
                "old vehicle removal packets must retain entity-world dimension routing");
        AnnotationNode redirect = packetRoute.visibleAnnotations.stream()
                .filter(annotation -> annotation.desc.endsWith("/Redirect;")).findFirst().orElseThrow();
        int methodIndex = redirect.values.indexOf("method");
        check(redirect.values.get(methodIndex + 1).equals(java.util.List.of("removePairing", "addPairing")),
                "the shared pairing handler must preserve both native send targets");
    }

    private static void nativeOnly(Class<?> type) throws Exception {
        RECORDS.clear(); nativeUpdates = 0;
        Object fixture = instance(type);
        Player player = new Player("source"); player.nativeVisible = true;
        updatePlayer(fixture, player);
        check(seen(fixture).contains(player.connection), "native visibility must add local player");
        check(entry(fixture).adds == 1, "local spawn must be sent once");
        tick(fixture); tick(fixture);
        check(nativeUpdates == 1, "no camera records must skip refreshing ordinary local seenBy");
        check(entry(fixture).adds == 1 && entry(fixture).removes == 0, "remote refresh must leave ordinary native pairing unchanged");
        player.nativeVisible = false; updatePlayer(fixture, player);
        check(seen(fixture).isEmpty() && entry(fixture).removes == 1, "native visibility must still remove local player");
    }

    private static void remote(Class<?> type) throws Exception {
        RECORDS.clear(); nativeUpdates = 0;
        Object fixture = instance(type);
        Player player = new Player("target");
        Watch watch = record(player); watch.isLoadedToPlayer = false;
        tick(fixture);
        check(seen(fixture).isEmpty(), "unloaded remote chunk must not spawn entity");
        watch.isLoadedToPlayer = true;
        tick(fixture); tick(fixture);
        check(seen(fixture).contains(player.connection) && entry(fixture).adds == 1, "loaded remote record must pair exactly once");
        updatePlayer(fixture, player);
        check(seen(fixture).contains(player.connection) && nativeUpdates == 0, "vanilla local distance update must not remove remote viewer");
        watch.distanceToSource = 8;
        tick(fixture);
        check(seen(fixture).isEmpty() && entry(fixture).removes == 1, "remote viewer leaving entity range must unpair");
        watch.distanceToSource = 0; watch.isValid = false;
        tick(fixture);
        check(entry(fixture).adds == 1, "invalid delayed record must not re-pair");
        watch.isValid = true; tick(fixture);
        RECORDS.clear(); tick(fixture);
        check(seen(fixture).isEmpty() && additional(fixture).isEmpty(), "closed remote subscription must clean seenBy and additional watcher");
    }

    private static void sourceExtra(Class<?> type) throws Exception {
        RECORDS.clear(); nativeUpdates = 0;
        Object fixture = instance(type);
        Player player = new Player("source");
        record(player); tick(fixture);
        check(seen(fixture).contains(player.connection) && nativeUpdates == 0, "same-world camera loader must supplement native view distance");
        RECORDS.clear(); tick(fixture);
        check(seen(fixture).isEmpty() && entry(fixture).removes == 1, "closing source loader must remove outside-native entity");
        record(player); player.nativeVisible = true; tick(fixture);
        RECORDS.clear(); tick(fixture);
        check(seen(fixture).contains(player.connection) && additional(fixture).isEmpty(), "closing source loader must keep still-native entity");
        int before = nativeUpdates; tick(fixture);
        check(nativeUpdates == before, "native member retained after loader close must leave supplemental refresh set");
    }

    private static void seamlessTransfer(Class<?> type) throws Exception {
        RECORDS.clear();
        Object fixture = instance(type);
        Player player = new Player("source"); player.nativeVisible = true;
        updatePlayer(fixture, player);
        type.getMethod("ip_onPlayerDimensionChange", Player.class).invoke(fixture, player);
        player.world = new World("target"); tick(fixture);
        check(seen(fixture).isEmpty(), "source native pairing must not leak after seamless dimension transfer");

        RECORDS.clear(); fixture = instance(type); player = new Player("source"); player.nativeVisible = true;
        updatePlayer(fixture, player); record(player);
        type.getMethod("ip_onPlayerDimensionChange", Player.class).invoke(fixture, player);
        player.world = new World("target"); tick(fixture);
        check(seen(fixture).contains(player.connection) && entry(fixture).adds == 1, "source pairing must survive while remote camera record still watches it");
        player.world = new World("source"); RECORDS.clear(); tick(fixture);
        check(seen(fixture).contains(player.connection) && additional(fixture).isEmpty(), "returning viewer must rejoin native tracking without duplicate spawn");
        check(entry(fixture).adds == 1 && entry(fixture).removes == 0, "valid source-remote-source handoff must preserve existing client entity");
    }

    private static void hiddenEntities(Class<?> type) throws Exception {
        for (String dimension : new String[] {"source", "target"}) {
            RECORDS.clear();
            Object fixture = instance(type);
            Entity entity = (Entity) type.getField("entity").get(fixture);
            Player player = new Player(dimension);
            record(player);
            entity.broadcastAllowed = false;
            tick(fixture);
            check(seen(fixture).isEmpty() && entry(fixture).adds == 0,
                    "hidden entities must not be paired through camera records in " + dimension);
            entity.broadcastAllowed = true;
            tick(fixture);
            check(seen(fixture).contains(player.connection) && entry(fixture).adds == 1,
                    "allowing visibility must pair a still-subscribed viewer in " + dimension);
            entity.broadcastAllowed = false;
            tick(fixture);
            check(seen(fixture).isEmpty() && additional(fixture).isEmpty() && entry(fixture).removes == 1,
                    "revoking visibility must unpair an existing camera viewer in " + dimension);
            entity.broadcastAllowed = true;
            tick(fixture);
            check(entry(fixture).adds == 2, "restoring visibility must pair again in " + dimension);
            entity.broadcastAllowed = false;
            player.nativeVisible = true;
            updatePlayer(fixture, player);
            tick(fixture);
            check(seen(fixture).isEmpty(), "native overlap must not bypass a hidden entity in " + dimension);
        }
    }

    private static void vehicleObserverCleanup(Class<?> type) throws Exception {
        RECORDS.clear();
        Object fixture = instance(type);
        Player rider = new Player("target");
        Player localObserver = new Player("source");
        Player cameraObserver = new Player("third");
        Player otherTraveler = new Player("fourth");
        for (Player player : new Player[] {rider, localObserver, cameraObserver, otherTraveler}) {
            seen(fixture).add(player.connection);
            additional(fixture).add(player.connection);
        }
        type.getMethod("ip_stopTrackingExcept", Player.class).invoke(fixture, rider);
        check(entry(fixture).removedPlayers.equals(Set.of(localObserver, cameraObserver, otherTraveler)),
                "old vehicle must be removed from every viewer except its exact transferring rider");
        check(entry(fixture).removes == 3, "old vehicle removal must be sent once to each other viewer");
        check(seen(fixture).isEmpty() && additional(fixture).isEmpty(),
                "discarded vehicle tracker must release all server watcher references");
        type.getMethod("ip_stopTrackingExcept", Player.class).invoke(fixture, rider);
        check(entry(fixture).removes == 3, "repeated cleanup must not duplicate removal packets");

        fixture = instance(type);
        seen(fixture).add(localObserver.connection);
        seen(fixture).add(cameraObserver.connection);
        type.getMethod("ip_stopTrackingExcept", Player.class).invoke(fixture, new Object[] {null});
        check(entry(fixture).removedPlayers.equals(Set.of(localObserver, cameraObserver)),
                "player entity transfer with no preserved viewer must still remove all source copies");
    }

    public static void updatePlayer(Object fixture, Player player) throws Exception {
        CallbackInfo info = new CallbackInfo("updatePlayer", true);
        fixture.getClass().getMethod("shuttershadow$keepRemoteWatcher", Player.class, CallbackInfo.class).invoke(fixture, player, info);
        if (info.isCancelled()) return;
        nativeUpdates++;
        Entity entity = (Entity) fixture.getClass().getField("entity").get(fixture);
        if (player.nativeVisible && entity.broadcastToPlayer(player)) {
            if (seen(fixture).add(player.connection)) entry(fixture).addPairing(player);
        } else if (seen(fixture).remove(player.connection)) entry(fixture).removePairing(player);
    }

    public static Object2ObjectOpenHashMap<Player, Watch> getWatchRecordForChunk(String dimension, int x, int z) {
        return RECORDS.get(dimension);
    }

    private static Watch record(Player player) {
        Watch watch = new Watch();
        RECORDS.computeIfAbsent("source", ignored -> new Object2ObjectOpenHashMap<>()).put(player, watch);
        return watch;
    }
    private static Object instance(Class<?> fixture) throws Exception {
        Object instance = fixture.getConstructor().newInstance();
        fixture.getField("entity").set(instance, new Entity("source"));
        fixture.getField("serverEntity").set(instance, new Entry());
        fixture.getField("seenBy").set(instance, new HashSet<Connection>());
        return instance;
    }
    @SuppressWarnings("unchecked")
    private static Set<Connection> seen(Object f) throws Exception { return (Set<Connection>) f.getClass().getField("seenBy").get(f); }
    @SuppressWarnings("unchecked")
    private static Set<Connection> additional(Object f) throws Exception { return (Set<Connection>) f.getClass().getField("shuttershadow$additionalWatchers").get(f); }
    private static Entry entry(Object f) throws Exception { return (Entry) f.getClass().getField("serverEntity").get(f); }
    private static void tick(Object f) throws Exception { f.getClass().getMethod("ip_updateEntityTrackingStatus").invoke(f); }
    private static void check(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
    private static boolean calls(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return true;
        return false;
    }
    private static int callCount(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) count++;
        }
        return count;
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }
    private static ClassNode read(Path compiled, String name) throws Exception {
        ClassNode node = new ClassNode(); new ClassReader(Files.readAllBytes(compiled.resolve(name + ".class"))).accept(node, 0); return node;
    }
    private static Class<?> fixture(ClassNode original) throws Exception {
        original.access &= ~Opcodes.ACC_ABSTRACT;
        original.interfaces.clear(); original.visibleAnnotations = null; original.invisibleAnnotations = null;
        original.methods.removeIf(m -> !(m.name.equals("<init>") || m.name.equals("getEffectiveRange") || m.name.equals("updatePlayer")
                || m.name.equals("shuttershadow$keepRemoteWatcher") || m.name.equals("shuttershadow$watchesAdditionalEntity")
                || m.name.equals("ip_updateEntityTrackingStatus") || m.name.equals("ip_onPlayerDimensionChange")
                || m.name.equals("ip_stopTrackingExcept") || m.name.startsWith("lambda$")));
        for (FieldNode field : original.fields) { field.access = Opcodes.ACC_PUBLIC; field.visibleAnnotations = null; field.invisibleAnnotations = null; }
        for (MethodNode method : original.methods) {
            method.access = Opcodes.ACC_PUBLIC; method.visibleAnnotations = null; method.invisibleAnnotations = null;
            if (method.name.equals("getEffectiveRange")) {
                method.instructions = new InsnList(); method.instructions.add(new IntInsnNode(Opcodes.SIPUSH, 128)); method.instructions.add(new InsnNode(Opcodes.IRETURN));
            } else if (method.name.equals("updatePlayer")) {
                method.instructions = new InsnList(); method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TEST, "updatePlayer", "(Ljava/lang/Object;L" + TEST + "$Player;)V", false));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
            }
        }
        Map<String, String> map = new HashMap<>();
        map.put(SOURCE, FIXTURE);
        map.put("net/minecraft/world/entity/Entity", TEST + "$Entity");
        map.put("net/minecraft/server/level/ServerPlayer", TEST + "$Player");
        map.put("net/minecraft/server/level/ServerEntity", TEST + "$Entry");
        map.put("net/minecraft/server/network/ServerPlayerConnection", TEST + "$Connection");
        map.put("net/minecraft/server/network/ServerGamePacketListenerImpl", TEST + "$GameConnection");
        map.put("net/minecraft/world/level/Level", TEST + "$World");
        map.put("net/minecraft/world/level/ChunkPos", TEST + "$Chunk");
        map.put("net/minecraft/resources/ResourceKey", "java/lang/String");
        map.put("com/xfw/shuttershadow/core/chunk_loading/RemoteChunkTracking", TEST);
        map.put("com/xfw/shuttershadow/core/chunk_loading/RemoteChunkTracking$PlayerWatchRecord", TEST + "$Watch");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        original.accept(new ClassRemapper(writer, new SimpleRemapper(map)));
        byte[] bytes = writer.toByteArray();
        ClassLoader loader = new ClassLoader(EntityTrackingTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(FIXTURE)) return defineClass(name, bytes, 0, bytes.length); return super.findClass(name);
            }
        };
        return loader.loadClass(FIXTURE);
    }

    public static class World {
        public String dimension;
        World(String dimension) { this.dimension = dimension.intern(); }
        public String dimension() { return dimension; }
    }
    public static class Entity {
        public World world;
        public boolean broadcastAllowed = true;
        Entity(String dimension) { world = new World(dimension); }
        public World level() { return world; }
        public Chunk chunkPosition() { return new Chunk(); }
        public boolean broadcastToPlayer(Player player) { return broadcastAllowed; }
    }
    public static class Player extends Entity {
        public GameConnection connection = new GameConnection(this);
        boolean nativeVisible;
        Player(String dimension) { super(dimension); }
    }
    public static class Chunk { public int x; public int z; }
    public interface Connection { Player getPlayer(); }
    public static class GameConnection implements Connection {
        Player player;
        GameConnection(Player player) { this.player = player; }
        public Player getPlayer() { return player; }
    }
    public static class Entry {
        int adds; int removes;
        final Set<Player> removedPlayers = new HashSet<>();
        public void addPairing(Player player) { adds++; }
        public void removePairing(Player player) { removes++; removedPlayers.add(player); }
    }
    public static class Watch {
        public boolean isLoadedToPlayer = true;
        public boolean isValid = true;
        public int distanceToSource;
    }
}

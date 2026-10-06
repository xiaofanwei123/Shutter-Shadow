"""Execute production chunk sending/redirection with small worlds; never boot Minecraft."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src/main/java/com/xfw/shuttershadow"
CORE = "com.xfw.shuttershadow.core.chunk_loading"
NETWORK = "com.xfw.shuttershadow.network"
UTIL = "com.xfw.shuttershadow.util"
PRODUCTION = (
    "core/chunk_loading/PlayerChunkLoading.java",
    "network/PacketRedirection.java",
    "network/PacketRedirectionClient.java",
    "network/RemoteChunkBatchReceivedC2S.java",
    "mixin/minecraft/server/MixinServerGamePacketListenerImpl_Redirect.java",
)


def declaration(path, signature):
    """Keep the current production declaration verbatim inside an environment fixture."""
    text = path.read_text(encoding="utf-8")
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


FIXTURES = {
    "org.slf4j.Logger": "public class Logger { public void error(String text, Object... values) {} }",
    "org.slf4j.LoggerFactory": "public class LoggerFactory { public static Logger getLogger(Class<?> cls) { return new Logger(); } }",
    "com.mojang.logging.LogUtils": "public class LogUtils { public static org.slf4j.Logger getLogger() { return new org.slf4j.Logger(); } }",
    "org.apache.commons.lang3.Validate": """public class Validate {
        public static void isTrue(boolean value) { if (!value) throw new IllegalArgumentException(); }
        public static <T> T notNull(T value, String... message) { if (value == null) throw new NullPointerException(); return value; }
    }""",
    "org.apache.commons.lang3.mutable.MutableInt": """public class MutableInt {
        private int value; public MutableInt(int value) { this.value = value; }
        public Integer getValue() { return value; } public void increment() { value++; }
    }""",
    "org.apache.commons.lang3.mutable.MutableBoolean": """public class MutableBoolean {
        private boolean value; public MutableBoolean(boolean value) { this.value = value; }
        public boolean booleanValue() { return value; } public void setValue(boolean value) { this.value = value; }
    }""",
    "it.unimi.dsi.fastutil.objects.ObjectList": """public interface ObjectList<T> extends java.util.List<T> {
        default void removeElements(int start, int end) { subList(start, end).clear(); }
    }""",
    "it.unimi.dsi.fastutil.objects.ObjectArrayList": "public class ObjectArrayList<T> extends java.util.ArrayList<T> implements ObjectList<T> {}",
    "it.unimi.dsi.fastutil.objects.ObjectOpenHashSet": "public class ObjectOpenHashSet<T> extends java.util.HashSet<T> {}",
    "net.minecraft.resources.ResourceKey": "public record ResourceKey<T>(String location) {}",
    "net.minecraft.resources.ResourceLocation": """public record ResourceLocation(String value) {
        public static ResourceLocation parse(String value) { return new ResourceLocation(value); }
        public static ResourceLocation fromNamespaceAndPath(String namespace, String path) { return parse(namespace + ":" + path); }
        public String toString() { return value; }
    }""",
    "net.minecraft.core.registries.Registries": "public class Registries { public static final Object DIMENSION = new Object(); }",
    "net.minecraft.world.level.Level": """public class Level {
        protected net.minecraft.resources.ResourceKey<Level> dimension;
        public Level(net.minecraft.resources.ResourceKey<Level> dimension) { this.dimension = dimension; }
        public net.minecraft.resources.ResourceKey<Level> dimension() { return dimension; }
    }""",
    "net.minecraft.world.level.ChunkPos": """public record ChunkPos(int x, int z) {
        public static long asLong(int x, int z) { return Integer.toUnsignedLong(x) | ((long) z << 32); }
        public static int getX(long pos) { return (int) pos; } public static int getZ(long pos) { return (int) (pos >>> 32); }
        public long toLong() { return asLong(x, z); }
    }""",
    "net.minecraft.util.Mth": "public class Mth { public static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); } }",
    "net.minecraft.network.protocol.Packet": "public interface Packet<T> { void handle(T listener); }",
    "net.minecraft.network.protocol.game.ClientGamePacketListener": "public interface ClientGamePacketListener extends net.minecraft.network.protocol.common.ClientCommonPacketListener { void onStart(); void onFinished(int size); void onChunk(long pos); }",
    "net.minecraft.network.protocol.common.ClientCommonPacketListener": "public interface ClientCommonPacketListener {}",
    "net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket": """public class ClientboundChunkBatchStartPacket implements net.minecraft.network.protocol.Packet<ClientGamePacketListener> {
        public static final ClientboundChunkBatchStartPacket INSTANCE = new ClientboundChunkBatchStartPacket();
        public void handle(ClientGamePacketListener listener) { listener.onStart(); }
    }""",
    "net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket": """public record ClientboundChunkBatchFinishedPacket(int batchSize) implements net.minecraft.network.protocol.Packet<ClientGamePacketListener> {
        public void handle(ClientGamePacketListener listener) { listener.onFinished(batchSize); }
    }""",
    "net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket": """public class ClientboundLevelChunkWithLightPacket implements net.minecraft.network.protocol.Packet<ClientGamePacketListener> {
        public final long chunkPos;
        public ClientboundLevelChunkWithLightPacket(net.minecraft.world.level.chunk.LevelChunk chunk, Object lightEngine, Object sky, Object block) { chunkPos = chunk.getPos().toLong(); }
        public void handle(ClientGamePacketListener listener) { listener.onChunk(chunkPos); }
    }""",
    "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket": """public record ClientboundCustomPayloadPacket(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) implements net.minecraft.network.protocol.Packet<ClientCommonPacketListener> {
        public void handle(ClientCommonPacketListener listener) {}
    }""",
    "net.minecraft.network.protocol.common.custom.CustomPacketPayload": """public interface CustomPacketPayload {
        record Type<T extends CustomPacketPayload>(net.minecraft.resources.ResourceLocation id) {}
        Type<? extends CustomPacketPayload> type();
    }""",
    "net.minecraft.network.protocol.BundleDelimiterPacket": "public class BundleDelimiterPacket<T> implements Packet<T> { public void handle(T listener) {} }",
    "net.minecraft.network.protocol.BundlePacket": """public class BundlePacket<T> implements Packet<T> {
        protected final Iterable<Packet<? super T>> packets;
        public BundlePacket(Iterable<Packet<? super T>> packets) { this.packets = packets; }
        public Iterable<Packet<? super T>> subPackets() { return packets; }
        public void handle(T listener) { for (Packet<? super T> packet : packets) packet.handle(listener); }
    }""",
    "net.minecraft.network.protocol.game.ClientboundBundlePacket": """public class ClientboundBundlePacket extends net.minecraft.network.protocol.BundlePacket<ClientGamePacketListener> {
        public ClientboundBundlePacket(Iterable<net.minecraft.network.protocol.Packet<? super ClientGamePacketListener>> packets) { super(packets); }
    }""",
    "net.minecraft.network.codec.StreamCodec": """public interface StreamCodec<B, T> {
        void encode(B buf, T value); T decode(B buf);
        static <B, T> StreamCodec<B, T> of(java.util.function.BiConsumer<B, T> writer, java.util.function.Function<B, T> reader) {
            return new StreamCodec<>() { public void encode(B buf, T value) { writer.accept(buf, value); } public T decode(B buf) { return reader.apply(buf); } };
        }
    }""",
    "net.minecraft.network.FriendlyByteBuf": """public class FriendlyByteBuf {
        private float value; private net.minecraft.resources.ResourceKey<?> key;
        public void writeFloat(float value) { this.value = value; } public float readFloat() { return value; }
        public void writeResourceKey(net.minecraft.resources.ResourceKey<?> key) { this.key = key; }
        @SuppressWarnings("unchecked") public <T> net.minecraft.resources.ResourceKey<T> readResourceKey(Object registry) { return (net.minecraft.resources.ResourceKey<T>) key; }
    }""",
    "net.minecraft.network.RegistryFriendlyByteBuf": "public class RegistryFriendlyByteBuf extends FriendlyByteBuf {}",
    "net.minecraft.network.ProtocolInfo": """public class ProtocolInfo<T> {
        public net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, net.minecraft.network.protocol.Packet<? super T>> codec() {
            return net.minecraft.network.codec.StreamCodec.of((buf, packet) -> {}, buf -> null);
        }
    }""",
    "net.minecraft.network.protocol.game.GameProtocols": """public class GameProtocols {
        public static final Template CLIENTBOUND_TEMPLATE = new Template();
        public static class Template { public net.minecraft.network.ProtocolInfo<ClientGamePacketListener> bind(java.util.function.Function<Object, net.minecraft.network.RegistryFriendlyByteBuf> convert) { return new net.minecraft.network.ProtocolInfo<>(); } }
    }""",
    "net.minecraft.network.PacketSendListener": "public interface PacketSendListener {}",
    "net.minecraft.server.MinecraftServer": """public class MinecraftServer {
        public final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, net.minecraft.server.level.ServerLevel> levels = new java.util.HashMap<>();
        public net.minecraft.server.level.ServerLevel getLevel(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) { return levels.get(dimension); }
    }""",
    "net.minecraft.server.level.ServerLevel": """public class ServerLevel extends net.minecraft.world.level.Level implements com.xfw.shuttershadow.access.IEWorld {
        public final Source source = new Source();
        public ServerLevel(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) { super(dimension); }
        public Source getChunkSource() { return source; } public Object getLightEngine() { return new Object(); }
        public Thread portal_getThread() { return Thread.currentThread(); }
        public static class Source { public final ChunkMap chunkMap = new ChunkMap(); }
    }""",
    "net.minecraft.server.level.ChunkMap": """public class ChunkMap implements com.xfw.shuttershadow.access.IEChunkMap {
        public final java.util.Map<Long, ChunkHolder> holders = new java.util.HashMap<>();
        public ChunkHolder ip_getChunkHolder(long pos) { return holders.get(pos); }
    }""",
    "net.minecraft.server.level.ChunkHolder": """public class ChunkHolder {
        public net.minecraft.world.level.chunk.LevelChunk ready; public int sendQueries;
        public net.minecraft.world.level.chunk.LevelChunk getChunkToSend() { sendQueries++; return ready; }
    }""",
    "net.minecraft.world.level.chunk.LevelChunk": """public class LevelChunk {
        private final net.minecraft.world.level.ChunkPos pos;
        public LevelChunk(int x, int z) { pos = new net.minecraft.world.level.ChunkPos(x, z); }
        public net.minecraft.world.level.ChunkPos getPos() { return pos; }
        public AuxLight getAuxLightManager(net.minecraft.world.level.ChunkPos pos) { return new AuxLight(); }
        public static class AuxLight { public <T> net.minecraft.network.protocol.Packet<T> sendLightDataTo(net.minecraft.network.protocol.Packet<T> packet) { return packet; } }
    }""",
    "net.minecraft.world.level.chunk.ChunkAccess": "public class ChunkAccess {}",
    "com.llamalad7.mixinextras.injector.wrapoperation.Operation": "public interface Operation<T> { T call(Object... args); }",
    "net.minecraft.server.level.ServerPlayer": """public class ServerPlayer {
        public final net.minecraft.server.MinecraftServer server;
        public final net.minecraft.server.network.ServerGamePacketListenerImpl connection;
        public ServerLevel world; private final View view = new View();
        public ServerPlayer(net.minecraft.server.MinecraftServer server, ServerLevel world) { this.server = server; this.world = world; connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(this); }
        public ServerLevel level() { return world; } public View getChunkTrackingView() { return view; }
        public static class View { public final java.util.Set<Long> chunks = new java.util.HashSet<>(); public boolean contains(int x, int z) { return chunks.contains(net.minecraft.world.level.ChunkPos.asLong(x, z)); } }
    }""",
    "net.minecraft.server.network.PlayerChunkSender": """public class PlayerChunkSender {
        public final java.util.Set<Long> pending = new java.util.HashSet<>(); public int nativeAcks;
        public boolean isPending(long pos) { return pending.contains(pos); }
        public void onChunkBatchReceivedByClient(float desired) { nativeAcks++; }
    }""",
    "net.minecraft.server.network.ServerCommonPacketListenerImpl": """public class ServerCommonPacketListenerImpl extends com.xfw.shuttershadow.mixin.minecraft.server.MixinServerGamePacketListenerImpl_Redirect {
        public final java.util.List<net.minecraft.network.protocol.Packet<?>> sent = new java.util.ArrayList<>();
        public ServerCommonPacketListenerImpl(net.minecraft.server.MinecraftServer server) { this.server = server; }
        public void send(net.minecraft.network.protocol.Packet<?> packet) {
            try {
                var method = com.xfw.shuttershadow.mixin.minecraft.server.MixinServerGamePacketListenerImpl_Redirect.class.getDeclaredMethod("modifyPacket", net.minecraft.network.protocol.Packet.class);
                method.setAccessible(true); sent.add((net.minecraft.network.protocol.Packet<?>) method.invoke(this, packet));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
    }""",
    "net.minecraft.server.network.ServerGamePacketListenerImpl": """public class ServerGamePacketListenerImpl extends ServerCommonPacketListenerImpl {
        public final net.minecraft.server.level.ServerPlayer player; public final PlayerChunkSender chunkSender = new PlayerChunkSender();
        public ServerGamePacketListenerImpl(net.minecraft.server.level.ServerPlayer player) { super(player.server); this.player = player; }
        public net.minecraft.server.level.ServerPlayer getPlayer() { return player; }
    }""",
    "net.neoforged.neoforge.event.EventHooks": "public class EventHooks { public static int sends; public static void fireChunkSent(Object player, Object chunk, Object level) { sends++; } }",
    "net.minecraft.client.multiplayer.ChunkBatchSizeCalculator": """public class ChunkBatchSizeCalculator {
        public int starts, finishes, lastSize;
        public void onBatchStart() { starts++; } public void onBatchFinished(int size) { finishes++; lastSize = size; }
        public float getDesiredChunksPerTick() { return lastSize + 0.5F; }
    }""",
    "net.minecraft.client.Minecraft": """public class Minecraft {
        private static final Minecraft INSTANCE = new Minecraft(); public boolean sameThread = true;
        public final java.util.ArrayDeque<Runnable> work = new java.util.ArrayDeque<>();
        public static Minecraft getInstance() { return INSTANCE; } public boolean isSameThread() { return sameThread; }
        public void execute(Runnable action) { work.add(action); }
        public void drain() { sameThread = true; while (!work.isEmpty()) work.remove().run(); }
    }""",
    "net.neoforged.neoforge.network.PacketDistributor": """public class PacketDistributor {
        public static final java.util.List<net.minecraft.network.protocol.common.custom.CustomPacketPayload> serverPackets = new java.util.ArrayList<>();
        public static void sendToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) { serverPackets.add(payload); }
    }""",
    "net.neoforged.neoforge.network.handling.IPayloadContext": "public interface IPayloadContext { Object player(); void enqueueWork(Runnable task); }",
    "com.xfw.shuttershadow.access.IEWorld": "public interface IEWorld { Thread portal_getThread(); }",
    "com.xfw.shuttershadow.access.IEChunkMap": "public interface IEChunkMap { net.minecraft.server.level.ChunkHolder ip_getChunkHolder(long pos); }",
    "com.xfw.shuttershadow.core.VanillaRuntimeHooks": "public @interface VanillaRuntimeHooks {}",
    "com.xfw.shuttershadow.Shuttershadow": "public class Shuttershadow { public static final String MODID = \"shuttershadow\"; }",
    "com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager": """public class ServerTeleportationManager {
        private static final ServerTeleportationManager INSTANCE = new ServerTeleportationManager();
        public final java.util.Set<net.minecraft.server.level.ServerPlayer> teleporting = new java.util.HashSet<>();
        public static ServerTeleportationManager of(net.minecraft.server.MinecraftServer server) { return INSTANCE; }
        public boolean isTeleporting(net.minecraft.server.level.ServerPlayer player) { return teleporting.contains(player); }
    }""",
    "com.xfw.shuttershadow.core.ClientWorldLoader": """public class ClientWorldLoader {
        public static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> active; public static int switches;
        public static void withSwitchedWorldFailSoft(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, Runnable action) {
            var previous = active; active = dimension; switches++;
            try { action.run(); } finally { active = previous; }
        }
    }""",
    "com.xfw.shuttershadow.mixin.minecraft.client.MixinMinecraft_RedirectedPacket": "public class MixinMinecraft_RedirectedPacket {}",
    UTIL + ".McHelper": "public class McHelper { public static net.minecraft.resources.ResourceLocation newResourceLocation(String value) { return net.minecraft.resources.ResourceLocation.parse(value); } }",
    "com.xfw.shuttershadow.api.ChunkLoader": "public class ChunkLoader {}",
    "org.spongepowered.asm.mixin.injection.callback.CallbackInfo": "public class CallbackInfo { public void cancel() {} }",
}

for annotation in ("org.jetbrains.annotations.Nullable", "org.jetbrains.annotations.NotNull",
                   "org.spongepowered.asm.mixin.Final", "org.spongepowered.asm.mixin.Shadow"):
    FIXTURES[annotation] = "@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE, java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PARAMETER}) public @interface " + annotation.rsplit(".", 1)[1] + " {}"
FIXTURES["org.spongepowered.asm.mixin.Mixin"] = "public @interface Mixin { Class<?>[] value(); }"
FIXTURES["org.spongepowered.asm.mixin.injection.At"] = "public @interface At { String value(); String target() default \"\"; }"
FIXTURES["org.spongepowered.asm.mixin.injection.Inject"] = "public @interface Inject { String method(); At at(); boolean cancellable() default false; }"
FIXTURES["org.spongepowered.asm.mixin.injection.ModifyVariable"] = "public @interface ModifyVariable { String method(); At at(); boolean argsOnly() default false; }"


def environment_fixtures():
    fixtures = dict(FIXTURES)
    chunk_map = JAVA_ROOT / "mixin/minecraft/server/MixinChunkMap_C.java"
    fixtures["com.xfw.shuttershadow.mixin.minecraft.server.MixinChunkMap_C"] = """
        import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
        import com.xfw.shuttershadow.network.PacketRedirection;
        import net.minecraft.server.level.ServerLevel;
        import net.minecraft.world.level.chunk.ChunkAccess;
        import java.util.List;
        public class MixinChunkMap_C {
            private final ServerLevel level;
            public MixinChunkMap_C(ServerLevel level) { this.level = level; }
            public void runBiomeUpdates(List<ChunkAccess> chunks, Operation<Void> original) {
                shuttershadow$routeBiomeUpdates(chunks, original);
            }
    """ + declaration(chunk_map, "private void shuttershadow$routeBiomeUpdates") + "}"
    tracking = JAVA_ROOT / "core/chunk_loading/RemoteChunkTracking.java"
    fixtures[CORE + ".RemoteChunkTracking"] = """
        import net.minecraft.resources.ResourceKey;
        import net.minecraft.server.level.ServerPlayer;
        import net.minecraft.world.level.Level;
        import net.minecraft.world.level.ChunkPos;
        public class RemoteChunkTracking {
            public static final java.util.Map<ServerPlayer, PlayerChunkLoading> infos = new java.util.HashMap<>();
            public static PlayerChunkLoading getPlayerInfo(ServerPlayer player) { return infos.get(player); }
    """ + declaration(tracking, "public static class PlayerWatchRecord") + declaration(
        tracking, "public static boolean isNativeChunkTracked") + "}"
    helper = JAVA_ROOT / "util/Helper.java"
    fixtures[UTIL + ".Helper"] = """
        import it.unimi.dsi.fastutil.objects.ObjectList;
        import org.apache.commons.lang3.mutable.MutableBoolean;
        import java.util.List;
        import java.util.function.BiPredicate;
        import java.util.function.Supplier;
        public class Helper {
    """ + declaration(helper, "public static <T> void removeIfWithEarlyExit") + declaration(
        helper, "public static <T> T arrayListComputeIfAbsent") + "}"
    return fixtures


class NativeChunkSyncTests(unittest.TestCase):
    def test_native_mixin_targets(self):
        """Require current compile output and compare every new selector with the real game."""
        compiled = ROOT / "build/classes/java/main"
        minecraft = ROOT / "build/moddev/artifacts/neoforge-21.1.252.jar"
        legacy = ROOT / "build/moddev/clientLegacyClasspath.txt"
        for required in (compiled, minecraft, legacy):
            self.assertTrue(required.exists(), "Run the normal build first: " + str(required))
        java = shutil.which("java")
        self.assertIsNotNone(java, "JDK 21 java is required")
        modules = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
        mixin_extras = [path for path in (modules / "io.github.llamalad7/mixinextras-neoforge").rglob("*.jar")
                        if not any(label in path.name for label in ("sources", "javadoc"))]
        self.assertTrue(mixin_extras, "Existing MixinExtras development dependency is required")
        classpath = os.pathsep.join([str(compiled), str(minecraft), str(mixin_extras[-1]), *legacy.read_text(encoding="utf-8").splitlines()])
        with tempfile.TemporaryDirectory(prefix="shuttershadow-biome-target-") as temporary:
            compilation = subprocess.run([shutil.which("javac"), "-encoding", "UTF-8", "--release", "21", "-proc:none",
                                          "-cp", classpath, "-d", temporary, str(JAVA_ROOT / "mixin/minecraft/server/MixinChunkMap_C.java")],
                                         capture_output=True, text=True)
            self.assertEqual(compilation.returncode, 0, compilation.stdout + compilation.stderr)
            execution = subprocess.run([java, "-ea", "-cp", classpath,
                                        str(ROOT / "tools/tests/NativeChunkTargetsTest.java"), str(minecraft), str(compiled), temporary],
                                       capture_output=True, text=True)
            self.assertEqual(execution.returncode, 0, execution.stdout + execution.stderr)
            print(execution.stdout.strip(), flush=True)

    def test_production_chunk_sync(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertIsNotNone(javac, "JDK 21 javac is required")
        self.assertIsNotNone(java, "JDK 21 java is required")
        with tempfile.TemporaryDirectory(prefix="shuttershadow-native-chunk-") as temporary:
            folder = Path(temporary)
            sources = []
            for name, body in environment_fixtures().items():
                path = folder / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf-8")
                sources.append(str(path))
            sources += [str(JAVA_ROOT / path) for path in PRODUCTION]
            sources.append(str(ROOT / "tools/tests/NativeChunkSyncTest.java"))
            compilation = subprocess.run([javac, "-encoding", "UTF-8", "--release", "21", "-d", str(folder), *sources],
                                         capture_output=True, text=True)
            self.assertEqual(compilation.returncode, 0, compilation.stdout + compilation.stderr)
            for scenario in ("native-pending", "ready-to-send", "source-extra", "remote", "invalid-records",
                             "quota", "ack-underflow", "local-updates", "biome-updates", "client-batches", "client-context", "ack-handler"):
                with self.subTest(scenario=scenario):
                    execution = subprocess.run([java, "-ea", "-cp", str(folder), "NativeChunkSyncTest", scenario],
                                               capture_output=True, text=True)
                    self.assertEqual(execution.returncode, 0, execution.stdout + execution.stderr)
                    print(execution.stdout.strip(), flush=True)


if __name__ == "__main__":
    unittest.main()

import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.core.chunk_loading.PlayerChunkLoading;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;
import com.xfw.shuttershadow.network.PacketRedirection;
import com.xfw.shuttershadow.network.PacketRedirectionClient;
import com.xfw.shuttershadow.network.RemoteChunkBatchReceivedC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayDeque;

/** 用假的世界和连接执行生产发送器、包装器、客户端处理器及回执，不启动游戏。 */
public final class NativeChunkSyncTest {
    private static final ResourceKey<Level> SOURCE = new ResourceKey<>("source");
    private static final ResourceKey<Level> REMOTE = new ResourceKey<>("remote");
    private static int checks;

    private static final class Environment {
        final MinecraftServer server = new MinecraftServer();
        final ServerLevel source = new ServerLevel(SOURCE);
        final ServerLevel remote = new ServerLevel(REMOTE);
        final ServerPlayer player = new ServerPlayer(server, source);
        final PlayerChunkLoading info;

        Environment(boolean memory) {
            server.levels.put(SOURCE, source);
            server.levels.put(REMOTE, remote);
            info = new PlayerChunkLoading(memory);
            RemoteChunkTracking.infos.put(player, info);
        }

        RemoteChunkTracking.PlayerWatchRecord record(ResourceKey<Level> dimension, int x, int z) {
            var record = new RemoteChunkTracking.PlayerWatchRecord(player, dimension,
                    ChunkPos.asLong(x, z), 1, 0, false, false);
            info.markPendingLoading(record);
            return record;
        }

        ChunkHolder ready(ServerLevel level, int x, int z) {
            var holder = new ChunkHolder();
            holder.ready = new LevelChunk(x, z);
            level.source.chunkMap.holders.put(ChunkPos.asLong(x, z), holder);
            return holder;
        }

        int pending() { return info.distanceToPendingChunks.stream().filter(list -> list != null).mapToInt(java.util.List::size).sum(); }
        void send() { info.doChunkSending(player); }
    }

    private static final class NativeListener implements ClientGamePacketListener {
        final ChunkBatchSizeCalculator calculator = new ChunkBatchSizeCalculator();
        int chunks;
        public void onStart() { calculator.onBatchStart(); }
        public void onFinished(int size) { calculator.onBatchFinished(size); }
        public void onChunk(long pos) { chunks++; }
    }

    private static final class Context implements IPayloadContext {
        final Object player;
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        Context(Object player) { this.player = player; }
        public Object player() { return player; }
        public void enqueueWork(Runnable task) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "native-pending" -> nativePending();
            case "ready-to-send" -> readyToSend();
            case "source-extra" -> sourceExtra();
            case "remote" -> remote();
            case "invalid-records" -> invalidRecords();
            case "quota" -> quota();
            case "ack-underflow" -> ackUnderflow();
            case "local-updates" -> localUpdates();
            case "biome-updates" -> biomeUpdates();
            case "client-batches" -> clientBatches();
            case "client-context" -> clientContext();
            case "ack-handler" -> ackHandler();
            default -> throw new AssertionError("Unknown scenario: " + args[0]);
        }
        System.out.println("PASS: " + args[0] + " (" + checks + " checks)");
    }

    private static void nativePending() throws Exception {
        var env = new Environment(true);
        long pos = ChunkPos.asLong(-3, 5);
        var record = env.record(SOURCE, -3, 5);
        var holder = env.ready(env.source, -3, 5);
        env.player.getChunkTrackingView().chunks.add(pos);
        env.player.connection.chunkSender.pending.add(pos);
        env.send();
        check(!record.isLoadedToPlayer && env.pending() == 1, "native pending chunk remains pending to camera");
        check(env.player.connection.sent.isEmpty() && integer(env.info, "unacknowledgedBatches") == 0,
                "native pending chunk must not duplicate packets or consume a camera batch");
        env.player.connection.chunkSender.pending.remove(pos);
        holder.ready = null;
        env.send();
        check(!record.isLoadedToPlayer && env.pending() == 1 && env.player.connection.sent.isEmpty(),
                "native pending completion cannot bypass getChunkToSend readiness");
        holder.ready = new LevelChunk(-3, 5);
        env.send();
        check(record.isLoadedToPlayer && env.pending() == 0, "native completion satisfies camera subscription");
        check(holder.sendQueries == 3 && env.player.connection.sent.isEmpty(), "native chunks use readiness query without duplicate sends");
        check(EventHooks.sends == 0, "native completion must not duplicate NeoForge chunk-send event");
    }

    private static void readyToSend() {
        var env = new Environment(true);
        var record = env.record(SOURCE, 12, -7);
        env.send();
        check(!record.isLoadedToPlayer && env.pending() == 1 && env.player.connection.sent.isEmpty(), "missing holder cannot complete capture loading");
        var holder = env.ready(env.source, 12, -7);
        holder.ready = null;
        env.send();
        check(holder.sendQueries == 1 && !record.isLoadedToPlayer && env.pending() == 1, "getChunkToSend null keeps the subscription waiting");
        holder.ready = new LevelChunk(12, -7);
        env.send();
        check(holder.sendQueries == 2 && record.isLoadedToPlayer && env.pending() == 0, "completed getChunkToSend is sent once");
        check(env.player.connection.sent.size() == 3, "ready extra chunk sends one bounded batch");
    }

    private static void sourceExtra() throws Exception {
        var env = new Environment(true);
        var record = env.record(SOURCE, 100, 200);
        env.ready(env.source, 100, 200);
        env.send();
        var sent = env.player.connection.sent;
        check(record.isLoadedToPlayer && env.pending() == 0, "source extra scope is served by camera sender");
        check(sent.size() == 3 && redirected(sent.get(0), SOURCE) instanceof ClientboundChunkBatchStartPacket,
                "camera source batch start is wrapped even though the dimension is local");
        check(sent.get(1) instanceof ClientboundLevelChunkWithLightPacket chunk && chunk.chunkPos == record.chunkPos,
                "source extra chunk data uses the raw native channel");
        check(redirected(sent.get(2), SOURCE) instanceof ClientboundChunkBatchFinishedPacket done && done.batchSize() == 1,
                "source batch completion is independently wrapped");
        check(integer(env.info, "unacknowledgedBatches") == 1 && EventHooks.sends == 1, "one camera batch and one chunk event are counted");
        env.send();
        check(sent.size() == 3, "unacknowledged batch prevents repeated sending");
    }

    private static void remote() {
        var env = new Environment(true);
        long pos = ChunkPos.asLong(2, 4);
        env.player.getChunkTrackingView().chunks.add(pos);
        env.player.connection.chunkSender.pending.add(pos);
        var record = env.record(REMOTE, 2, 4);
        env.ready(env.remote, 2, 4);
        env.send();
        var sent = env.player.connection.sent;
        check(record.isLoadedToPlayer && sent.size() == 3, "matching source coordinates do not suppress remote chunks");
        check(redirected(sent.get(0), SOURCE) instanceof ClientboundChunkBatchStartPacket, "remote batch owns a camera start marker");
        check(redirected(sent.get(1), REMOTE) instanceof ClientboundLevelChunkWithLightPacket chunk && chunk.chunkPos == pos,
                "remote chunk goes to the remote dimension wrapper");
        check(redirected(sent.get(2), SOURCE) instanceof ClientboundChunkBatchFinishedPacket, "remote batch owns a camera finish marker");
        check(env.player.connection.chunkSender.pending.contains(pos), "camera sending cannot consume native pending chunks");
    }

    private static void invalidRecords() {
        var env = new Environment(true);
        var invalid = env.record(SOURCE, 0, 0); invalid.isValid = false;
        var loaded = env.record(SOURCE, 1, 1); loaded.isLoadedToPlayer = true;
        env.record(new ResourceKey<>("gone"), 2, 2);
        var waiting = env.record(REMOTE, 3, 3);
        env.send();
        check(env.pending() == 1 && !waiting.isLoadedToPlayer, "invalid, loaded and removed-world records are removed while ready work waits");
        check(env.player.connection.sent.isEmpty(), "invalid records never create empty batch markers");
    }

    private static void quota() throws Exception {
        var env = new Environment(false);
        for (int x = 0; x < 12; x++) { env.record(REMOTE, x, 0); env.ready(env.remote, x, 0); }
        env.send();
        check(env.pending() == 3 && env.player.connection.sent.size() == 11, "network quota bounds first batch to nine chunks");
        check(redirected(env.player.connection.sent.getLast(), SOURCE) instanceof ClientboundChunkBatchFinishedPacket done && done.batchSize() == 9,
                "finish marker counts exactly sent chunks");
        env.send();
        check(env.player.connection.sent.size() == 11, "first unacknowledged batch blocks another flush");
        env.info.onChunkBatchReceivedByClient(2);
        env.send();
        check(env.pending() == 1 && env.player.connection.sent.size() == 15, "camera ack updates only camera quota to two chunks");
        check(env.player.connection.chunkSender.nativeAcks == 0, "camera quota does not acknowledge native batches");
    }

    private static void ackUnderflow() throws Exception {
        var env = new Environment(false);
        env.info.onChunkBatchReceivedByClient(Float.NaN);
        check(integer(env.info, "unacknowledgedBatches") == 0 && floating(env.info, "desiredChunksPerTick") == 9,
                "unsolicited camera ack neither underflows nor changes desired throughput");
        check(integer(env.info, "maxUnacknowledgedBatches") == 1 && floating(env.info, "batchQuota") == 0,
                "unsolicited ack cannot unlock the initial throttle");
        set(env.info, "unacknowledgedBatches", 2);
        env.info.onChunkBatchReceivedByClient(Float.POSITIVE_INFINITY);
        check(integer(env.info, "unacknowledgedBatches") == 1 && floating(env.info, "desiredChunksPerTick") == 0.01F,
                "nonfinite legitimate ack uses bounded minimum throughput");
        env.info.onChunkBatchReceivedByClient(Float.MAX_VALUE);
        check(integer(env.info, "unacknowledgedBatches") == 0 && floating(env.info, "desiredChunksPerTick") == 64,
                "last legitimate ack is clamped to the upper bound");
        for (int i = 0; i < 100; i++) env.info.onChunkBatchReceivedByClient(-100);
        check(integer(env.info, "unacknowledgedBatches") == 0 && floating(env.info, "desiredChunksPerTick") == 64,
                "duplicate acknowledgements cannot drive the counter negative or overwrite throughput");
        check(floating(env.info, "batchQuota") == 1 && integer(env.info, "maxUnacknowledgedBatches") == 10,
                "only legitimate final ack resets quota and unlocks later batch count");
    }

    private static void localUpdates() {
        var env = new Environment(true);
        Packet<ClientGamePacketListener> update = listener -> listener.onChunk(42);
        PacketRedirection.sendRedirectedPacket(env.player.connection, update, SOURCE);
        check(env.player.connection.sent.getLast() == update, "ordinary source updates are the original raw object");
        PacketRedirection.sendRedirectedPacket(env.player.connection, update, REMOTE);
        var wrapped = env.player.connection.sent.getLast();
        check(redirected(wrapped, REMOTE) == update, "ordinary remote updates have exactly one wrapper");
        PacketRedirection.withForceRedirect(env.remote, () -> PacketRedirection.sendRedirectedPacket(env.player.connection, update, REMOTE));
        check(redirected(env.player.connection.sent.getLast(), REMOTE) == update, "forced remote send avoids duplicate nesting");
        PacketRedirection.withForceRedirect(env.source, () -> env.player.connection.send(update));
        check(env.player.connection.sent.getLast() == update, "forced local send still leaves ordinary source update raw");
        check(PacketRedirection.getForceRedirectDimension() == null, "force context is restored after each send");
        ServerTeleportationManager.of(env.server).teleporting.add(env.player);
        PacketRedirection.withForceRedirect(env.source, () -> env.player.connection.send(update));
        check(redirected(env.player.connection.sent.getLast(), SOURCE) == update, "source updates keep context wrapper during seamless transition");
        @SuppressWarnings("unchecked") var typedWrapped = (Packet<ClientGamePacketListener>) (Packet<?>) wrapped;
        check(PacketRedirection.createRedirectedMessage(env.server, REMOTE, typedWrapped) == wrapped, "already redirected messages never get nested");
    }

    private static void clientBatches() {
        var nativeListener = new NativeListener();
        ClientboundChunkBatchStartPacket.INSTANCE.handle(nativeListener);
        PacketRedirectionClient.handleRedirectedPacket(SOURCE, ClientboundChunkBatchStartPacket.INSTANCE, nativeListener);
        PacketRedirectionClient.handleRedirectedPacket(SOURCE, new ClientboundChunkBatchFinishedPacket(7), nativeListener);
        check(nativeListener.calculator.starts == 1 && nativeListener.calculator.finishes == 0,
                "wrapped source camera batch never calls the native batch listener");
        check(PacketDistributor.serverPackets.size() == 1 && PacketDistributor.serverPackets.getFirst() instanceof RemoteChunkBatchReceivedC2S ack
                        && ack.desiredChunksPerTick() == 7.5F, "camera calculator emits only the dedicated camera ack");
        check(ClientWorldLoader.switches == 0 && PacketRedirectionClient.clientTaskRedirection.get() == null,
                "camera batch markers do not switch client worlds or task context");
        new ClientboundChunkBatchFinishedPacket(17).handle(nativeListener);
        check(nativeListener.calculator.finishes == 1 && nativeListener.calculator.lastSize == 17,
                "native completion remains independent even around a nested camera batch");
    }

    private static void clientContext() {
        var nativeListener = new NativeListener();
        PacketRedirectionClient.clientTaskRedirection.set(SOURCE);
        PacketRedirectionClient.handleRedirectedPacket(REMOTE, (Packet<ClientGamePacketListener>) listener -> {
            check(PacketRedirectionClient.clientTaskRedirection.get() == REMOTE && ClientWorldLoader.active == REMOTE,
                    "remote data executes with both remote task and world contexts");
            listener.onChunk(1);
        }, nativeListener);
        check(PacketRedirectionClient.clientTaskRedirection.get() == SOURCE && ClientWorldLoader.active == null,
                "remote data restores prior task/world contexts");
        try {
            PacketRedirectionClient.handleRedirectedPacket(REMOTE, (Packet<ClientGamePacketListener>) listener -> { throw new IllegalStateException("fixture"); }, nativeListener);
            throw new AssertionError("fixture failure disappeared");
        } catch (IllegalStateException expected) {
            check(PacketRedirectionClient.clientTaskRedirection.get() == SOURCE && ClientWorldLoader.active == null,
                    "failed packet handling also restores task/world contexts");
        }
        Minecraft.getInstance().sameThread = false;
        PacketRedirectionClient.handleRedirectedPacket(REMOTE, ClientboundChunkBatchStartPacket.INSTANCE, nativeListener);
        check(Minecraft.getInstance().work.size() == 1 && PacketDistributor.serverPackets.isEmpty(),
                "network-thread camera batch waits for the client thread");
        Minecraft.getInstance().drain();
        PacketRedirectionClient.handleRedirectedPacket(REMOTE, new ClientboundChunkBatchFinishedPacket(3), nativeListener);
        check(PacketDistributor.serverPackets.size() == 1 && nativeListener.calculator.starts == 0,
                "scheduled camera start still belongs to the independent calculator");
        PacketRedirectionClient.resetChunkBatchCalculator();
        PacketRedirectionClient.handleRedirectedPacket(REMOTE, new ClientboundChunkBatchFinishedPacket(4), nativeListener);
        check(PacketDistributor.serverPackets.size() == 2, "calculator reset leaves dedicated ack path functional");
    }

    private static void ackHandler() throws Exception {
        var env = new Environment(true);
        env.record(REMOTE, 0, 0); env.ready(env.remote, 0, 0); env.send();
        var context = new Context(env.player);
        var ack = new RemoteChunkBatchReceivedC2S(7.5F);
        ack.handle(context);
        check(integer(env.info, "unacknowledgedBatches") == 1 && context.tasks.size() == 1, "dedicated ack waits for server-thread execution");
        context.drain();
        check(integer(env.info, "unacknowledgedBatches") == 0 && floating(env.info, "desiredChunksPerTick") == 7.5F,
                "dedicated ack reaches only this player's camera sender");
        check(env.player.connection.chunkSender.nativeAcks == 0, "dedicated server ack cannot acknowledge native sender");
        env.player.connection.chunkSender.onChunkBatchReceivedByClient(20);
        check(env.player.connection.chunkSender.nativeAcks == 1 && integer(env.info, "unacknowledgedBatches") == 0,
                "native acknowledgement leaves camera sender untouched");
        var invalidContext = new Context(new Object()); ack.handle(invalidContext); invalidContext.drain();
        check(integer(env.info, "unacknowledgedBatches") == 0, "non-player contexts cannot touch camera sender");
        var buffer = new FriendlyByteBuf();
        RemoteChunkBatchReceivedC2S.STREAM_CODEC.encode(buffer, ack);
        check(RemoteChunkBatchReceivedC2S.STREAM_CODEC.decode(buffer).equals(ack), "dedicated ack preserves throughput through its real codec");
    }

    private static void biomeUpdates() {
        var env = new Environment(true);
        var localPlayer = new ServerPlayer(env.server, env.remote);
        var map = new com.xfw.shuttershadow.mixin.minecraft.server.MixinChunkMap_C(env.remote);
        var chunks = java.util.List.of(new ChunkAccess(), new ChunkAccess());
        var first = new ClientboundLevelChunkWithLightPacket(new LevelChunk(7, -8), null, null, null);
        var second = new ClientboundLevelChunkWithLightPacket(new LevelChunk(9, -10), null, null, null);
        int[] calls = {0};
        map.runBiomeUpdates(chunks, arguments -> {
            calls[0]++;
            check(arguments.length == 1 && arguments[0] == chunks, "biome wrapper preserves the original list identity");
            check(PacketRedirection.getForceRedirectDimension() == REMOTE, "biome generation and every send run in the target context");
            localPlayer.connection.send(first);
            env.player.connection.send(first);
            localPlayer.connection.send(second);
            env.player.connection.send(second);
            return null;
        });
        check(calls[0] == 1, "biome wrapper invokes the native operation exactly once");
        check(localPlayer.connection.sent.equals(java.util.List.of(first, second)), "local biome recipient retains raw native packets");
        check(env.player.connection.sent.size() == 2, "remote biome recipient receives the original count of packets");
        check(redirected(env.player.connection.sent.get(0), REMOTE) == first
                        && redirected(env.player.connection.sent.get(1), REMOTE) == second,
                "remote biome packets retain packet identity and target dimension");
        check(PacketRedirection.getForceRedirectDimension() == null, "normal biome completion restores an empty context");
        RuntimeException failure = new IllegalStateException("biome generation failed");
        try {
            PacketRedirection.withForceRedirect(env.source, () -> {
                try {
                    map.runBiomeUpdates(chunks, arguments -> {
                        check(PacketRedirection.getForceRedirectDimension() == REMOTE, "nested biome operation temporarily uses its own dimension");
                        throw failure;
                    });
                } finally {
                    check(PacketRedirection.getForceRedirectDimension() == SOURCE, "failed biome operation restores the prior redirect context");
                }
            });
            throw new AssertionError("biome operation failure must propagate");
        } catch (RuntimeException exception) {
            check(exception == failure, "biome operation exception is propagated unchanged");
        }
        check(PacketRedirection.getForceRedirectDimension() == null, "nested failed biome operation leaves no redirect context behind");
    }

    private static Packet<?> redirected(Packet<?> packet, ResourceKey<Level> dimension) {
        check(packet instanceof ClientboundCustomPayloadPacket, "expected a custom packet wrapper");
        var payload = ((ClientboundCustomPayloadPacket) packet).payload();
        check(payload instanceof PacketRedirection.Payload redirect && redirect.dimension() == dimension, "wrapper uses the expected dimension identity");
        return ((PacketRedirection.Payload) payload).packet();
    }

    private static int integer(Object target, String name) throws Exception { return (Integer) field(target, name); }
    private static float floating(Object target, String name) throws Exception { return (Float) field(target, name); }
    private static Object field(Object target, String name) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}

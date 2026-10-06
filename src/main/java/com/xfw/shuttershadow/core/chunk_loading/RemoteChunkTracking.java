package com.xfw.shuttershadow.core.chunk_loading;
import com.xfw.shuttershadow.api.ChunkLoader;
// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.logging.LogUtils;
import com.xfw.shuttershadow.event.ServerCleanupEvent;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import com.xfw.shuttershadow.access.IEChunkMap;
import com.xfw.shuttershadow.mixin.minecraft.server.IEServerCommonPacketListenerImpl;
import com.xfw.shuttershadow.network.PacketRedirection;

import java.util.*;
import java.util.function.Predicate;

public class RemoteChunkTracking {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int updateInterval = 13;
    public static final int defaultDelayUnloadGenerations = 4;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            RemoteChunkTracking.tick(event.getServer());
        });

        NeoForge.EVENT_BUS.addListener(ServerCleanupEvent.class, event -> {
            MinecraftServer server = event.server;
            cleanup(server);
        });

    }

    // if the player object is recreated, pass in the old player object
    public static void removePlayerFromChunkTrackersAndEntityTrackers(ServerPlayer oldPlayer) {
        for (ServerLevel world : oldPlayer.server.getAllLevels()) {
            ServerChunkCache chunkManager = world.getChunkSource();
            IEChunkMap storage =
                    (IEChunkMap) chunkManager.chunkMap;
            storage.ip_onPlayerUnload(oldPlayer);
        }

        forceRemovePlayer(oldPlayer);
    }

    public static class PlayerWatchRecord {
        public final ServerPlayer player;
        public final ResourceKey<Level> dimension;
        public final long chunkPos;
        public int lastWatchGeneration;
        public int distanceToSource;
        public boolean isLoadedToPlayer;
        public boolean isValid = true;
        public boolean isBoundary = false;
        // the light data is only sent on visibility boundary
        // as the client can calculate light from block data

        public PlayerWatchRecord(
                ServerPlayer player, ResourceKey<Level> dimension,
                long chunkPos, int lastWatchGeneration,
                int distanceToSource, boolean isLoadedToPlayer,
                boolean isBoundary
        ) {
            this.player = player;
            this.dimension = dimension;
            this.chunkPos = chunkPos;
            this.lastWatchGeneration = lastWatchGeneration;
            this.distanceToSource = distanceToSource;
            this.isLoadedToPlayer = isLoadedToPlayer;
            this.isBoundary = isBoundary;
        }

        @Override
        public String toString() {
            return String.format(
                    "%s (%d,%d) distance:%d valid:%s loaded:%s",
                    dimension.location(),
                    ChunkPos.getX(chunkPos),
                    ChunkPos.getZ(chunkPos),
                    distanceToSource,
                    isValid,
                    isLoadedToPlayer
            );
        }
    }

    // Every chunk has a list of watching records
    private static final Map<
            ResourceKey<Level>,
            Long2ObjectOpenHashMap<
                    Object2ObjectOpenHashMap<ServerPlayer, PlayerWatchRecord>>> chunkWatchRecords =
            new Object2ObjectOpenHashMap<>();

    private static final ArrayList<ChunkLoader> additionalChunkLoaders = new ArrayList<>();

    private static final Object2ObjectOpenHashMap<ServerPlayer, PlayerChunkLoading> playerInfoMap =
            new Object2ObjectOpenHashMap<>();

    private static int generationCounter = 0;

    private static Long2ObjectOpenHashMap<Object2ObjectOpenHashMap<ServerPlayer, PlayerWatchRecord>>
    getDimChunkWatchRecords(ResourceKey<Level> dimension) {
        return chunkWatchRecords.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>());
    }

    /** 原维度由 Minecraft 原生发送，仅相机订阅的额外范围才由内核同步。 */
    public static boolean isNativeChunkTracked(ServerPlayer player, ResourceKey<Level> dimension, int x, int z) {
        return player.level().dimension() == dimension && player.getChunkTrackingView().contains(x, z);
    }

    /** 原版视野变化后立即接续相机额外范围的票据，避免移动时出现加载空档。 */
    public static void onNativeViewChanged(ServerPlayer player) {
        PlayerChunkLoading info = playerInfoMap.get(player);
        if (info != null && !info.additionalChunkLoaders.isEmpty()) updateForPlayer(player);
    }

    public static PlayerChunkLoading getPlayerInfo(ServerPlayer player) {
        return playerInfoMap.computeIfAbsent(
                player,
                (ServerPlayer p) -> new PlayerChunkLoading(
                        ((IEServerCommonPacketListenerImpl) p.connection)
                                .ip_getConnection().isMemoryConnection()
                )
        );
    }

    public static void immediatelyUpdateForPlayer(ServerPlayer player) {
        RemoteChunkTracking.updateForPlayer(player);

        getPlayerInfo(player).doChunkSending(player);

        // 区块发送后再补充相机实体生成包。
        EntitySync.update(player.server);
    }

    public static void updateForPlayer(ServerPlayer player) {
        PlayerChunkLoading playerInfo = getPlayerInfo(player);
        playerInfo.visibleDimensions.clear();
        playerInfo.loadedChunks = 0;

        ObjectOpenHashSet<ChunkLoader> chunkLoaders = new ObjectOpenHashSet<>();

        chunkLoaders.addAll(playerInfo.additionalChunkLoaders);

        MinecraftServer server = player.server;

        for (ChunkLoader chunkLoader : chunkLoaders) {
            ResourceKey<Level> dimension = chunkLoader.dimension();
            var chunkRecordMap = getDimChunkWatchRecords(dimension);

            ServerLevel world = server.getLevel(dimension);
            if (world == null) {
                LOGGER.warn("Dimension not loaded {} in chunk loader {}", dimension, chunkLoader);
                return;
            }

            playerInfo.visibleDimensions.add(dimension);

            RemoteChunkTickets ticketInfo = RemoteChunkTickets.get(world);

            chunkLoader.foreachChunkPos((dim, x, z, distanceToSource) -> {
                long chunkPos = ChunkPos.asLong(x, z);
                var records =
                        chunkRecordMap.computeIfAbsent(chunkPos, k -> new Object2ObjectOpenHashMap<>());

                // 原版玩家视野内的源区块不再添加相机票据。
                if (!isNativeChunkTracked(player, dim, x, z)) {
                    ticketInfo.markForLoading(chunkPos, distanceToSource, generationCounter);
                }

                records.compute(player, (k, record) -> {
                    boolean isBoundary = distanceToSource == chunkLoader.radius();
                    if (record == null) {
                        PlayerWatchRecord newRecord = new PlayerWatchRecord(
                                player, dimension, chunkPos, generationCounter, distanceToSource,
                                false, isBoundary
                        );
                        playerInfo.markPendingLoading(newRecord);
                        playerInfo.loadedChunks++;
                        return newRecord;
                    } else {
                        int oldDistance = record.distanceToSource;
                        if (record.lastWatchGeneration == generationCounter) {
                            // being updated again in the same turn
                            if (distanceToSource < oldDistance) {
                                record.distanceToSource = distanceToSource;
                                playerInfo.markPendingLoading(record);
                            }

                            record.isBoundary = (record.isBoundary && isBoundary);
                        } else {
                            // being updated at the first time in this turn
                            playerInfo.loadedChunks++;
                            if (distanceToSource < oldDistance) {
                                playerInfo.markPendingLoading(record);
                            }

                            record.distanceToSource = distanceToSource;
                            record.lastWatchGeneration = generationCounter;
                            record.isBoundary = isBoundary;
                        }
                    }

                    return record;
                });
            });
        }
    }

    private static void purge(
            MinecraftServer server,
            Object2ObjectOpenHashMap<ResourceKey<Level>, LongOpenHashSet> additionalLoadedChunks
    ) {
        // purge chunk watch records
        chunkWatchRecords.forEach((dimension, chunkRecords) -> {
            chunkRecords.long2ObjectEntrySet().removeIf(entry -> {
                long chunkPosLong = entry.getLongKey();

                var dimChunkWatchRecords = entry.getValue();

                dimChunkWatchRecords.entrySet().removeIf(e -> {
                    ServerPlayer player = e.getKey();

                    if (player.isRemoved()) {
                        return true;
                    }

                    PlayerWatchRecord record = e.getValue();
                    int delayUnloadGenerations = getDelayUnloadGenerationForPlayer(player);
                    boolean shouldRemove = generationCounter - record.lastWatchGeneration > delayUnloadGenerations;

                    if (shouldRemove) {
                        if (record.isLoadedToPlayer && !isNativeChunkTracked(player, record.dimension,
                                ChunkPos.getX(record.chunkPos), ChunkPos.getZ(record.chunkPos))) {
                            EventHooks.fireChunkUnWatch(player, new ChunkPos(record.chunkPos), player.getServer().getLevel(record.dimension));
                            player.connection.send(
                                    PacketRedirection.createRedirectedMessage(
                                            player.getServer(),
                                            record.dimension,
                                            new ClientboundForgetLevelChunkPacket(
                                                    new ChunkPos(record.chunkPos)
                                            )
                                    )
                            );
                        }
                        record.isValid = false;
                    }

                    return shouldRemove;
                });

                return dimChunkWatchRecords.isEmpty();
            });
        });

        // purge player info map
        playerInfoMap.entrySet().removeIf(e -> e.getKey().isRemoved());

        for (ServerLevel world : server.getAllLevels()) {
            ResourceKey<Level> dimension = world.dimension();

            @Nullable LongOpenHashSet additional = additionalLoadedChunks.get(dimension);
            @Nullable var watchRecs =
                    chunkWatchRecords.get(dimension);

            RemoteChunkTickets dimTicketManager = RemoteChunkTickets.BY_DIMENSION.get(world);
            if (dimTicketManager == null) continue;

            dimTicketManager.purge(
                    world,
                    chunkPos -> {
                        if (watchRecs != null && watchRecs.containsKey(chunkPos)) {
                            for (PlayerWatchRecord rec : watchRecs.get(chunkPos).values()) {
                                if (!isNativeChunkTracked(rec.player, dimension,
                                        ChunkPos.getX(chunkPos), ChunkPos.getZ(chunkPos))) return true;
                            }
                        }
                        if (additional != null && additional.contains(chunkPos)) {
                            return true;
                        }
                        return false;
                    }
            );
        }
    }

    // unload chunks earlier if the player loads many chunks
    private static int getDelayUnloadGenerationForPlayer(ServerPlayer player) {
        PlayerChunkLoading playerInfo = getPlayerInfo(player);
        if (playerInfo == null) {
            return defaultDelayUnloadGenerations;
        }

        int loadedChunks = playerInfo.loadedChunks;

        if (loadedChunks > 2000) {
            return 1;
        }

        if (loadedChunks > 1200) {
            return 2;
        }

        return defaultDelayUnloadGenerations;
    }

    private static Object2ObjectOpenHashMap<ResourceKey<Level>, LongOpenHashSet> refreshAdditionalChunkLoaders(MinecraftServer server) {
        Object2ObjectOpenHashMap<ResourceKey<Level>, LongOpenHashSet> additionalLoadedChunks =
                new Object2ObjectOpenHashMap<>();

        additionalChunkLoaders.removeIf(chunkLoader -> {
            ResourceKey<Level> dimension = chunkLoader.dimension();
            ServerLevel world = server.getLevel(dimension);

            if (world == null) {
                LOGGER.error("Missing dimension in chunk loader {}", dimension.location());
                return true;
            }

            RemoteChunkTickets dimTicketManager = RemoteChunkTickets.get(world);

            LongOpenHashSet set = additionalLoadedChunks.computeIfAbsent(dimension, k -> new LongOpenHashSet());

            chunkLoader.foreachChunkPos(new ChunkLoader.ChunkPosConsumer() {
                @Override
                public void consume(ResourceKey<Level> dimension, int x, int z, int distanceToSource) {
                    long chunkPos = ChunkPos.asLong(x, z);
                    dimTicketManager.markForLoading(chunkPos, distanceToSource, generationCounter);
                    set.add(chunkPos);
                }
            });

            return false;
        });

        return additionalLoadedChunks;
    }

    private static void tick(MinecraftServer server) {
        server.getProfiler().push("shuttershadow_camera_tracking");

        long gameTime = server.overworld().getGameTime();
        for (var entry : playerInfoMap.entrySet()) {
            ServerPlayer player = entry.getKey();
            if (player.isRemoved()) continue;
            PlayerChunkLoading playerInfo = entry.getValue();

            // spread the player updates to different ticks
            if (playerInfo.shouldUpdateImmediately ||
                    ((player.getId() % updateInterval) == (gameTime % updateInterval))
            ) {
                playerInfo.shouldUpdateImmediately = false;
                updateForPlayer(player);
            }
        }
        if (gameTime % updateInterval == 0) {
            var additionalLoadedChunks = refreshAdditionalChunkLoaders(server);
            purge(server, additionalLoadedChunks);
            generationCounter++;
        }

        for (ServerLevel world : server.getAllLevels()) {
            RemoteChunkTickets dimTicketManager = RemoteChunkTickets.BY_DIMENSION.get(world);
            if (dimTicketManager != null) dimTicketManager.flushThrottling(world);
        }

        server.getProfiler().pop();

        // 原版负责实体 tick，此处只维护相机的额外观察者。
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerChunkLoading info = playerInfoMap.get(player);
            if (info != null) info.doChunkSending(player);
        }
        EntitySync.update(server);
    }

    public static boolean isPlayerWatchingChunk(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            int x, int z,
            Predicate<PlayerWatchRecord> predicate
    ) {
        long chunkPos = ChunkPos.asLong(x, z);

        var dimRecords = chunkWatchRecords.get(dimension);
        var recordMap = dimRecords == null ? null : dimRecords.get(chunkPos);
        if (recordMap == null) {
            return false;
        }

        PlayerWatchRecord record = recordMap.get(player);

        if (record == null) {
            return false;
        }

        if (!record.isValid || !record.isLoadedToPlayer) {
            return false;
        }

        return predicate.test(record);
    }

    public static boolean isPlayerWatchingChunk(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            int x, int z
    ) {
        return isPlayerWatchingChunk(player, dimension, x, z, r -> true);
    }

    public static boolean isPlayerWatchingChunkWithinRadius(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            int x, int z,
            int radiusBlocks
    ) {
        return isPlayerWatchingChunk(
                player, dimension, x, z,
                r -> r.distanceToSource * 16 <= radiusBlocks
        );
    }

    private static void cleanup(MinecraftServer server) {
        chunkWatchRecords.clear();
        additionalChunkLoaders.clear();
        playerInfoMap.clear();
    }

    /**
     * Note when update should also check {@link com.xfw.shuttershadow.mixin.minecraft.server.MixinPlayerList}
     */
    public static List<ServerPlayer> getPlayersViewingChunk(
            ResourceKey<Level> dimension,
            int x, int z,
            boolean boundaryOnly
    ) {
        var recs =
                RemoteChunkTracking.getWatchRecordForChunk(dimension, x, z);

        if (recs == null) {
            return Collections.emptyList();
        }

        // the boundaryOnly parameter is only true when sending light update packets
        // the client can calculate the light by the block data, but not accurate on loading boundary

        ArrayList<ServerPlayer> result = new ArrayList<>();
        for (RemoteChunkTracking.PlayerWatchRecord rec : recs.values()) {
            if (rec.isValid && rec.isLoadedToPlayer && (!boundaryOnly || rec.isBoundary)) {
                result.add(rec.player);
            }
        }

        return result;
    }

    // Note all PlayerWatchRecord taken from it are valid
    @Nullable
    public static Object2ObjectOpenHashMap<ServerPlayer, PlayerWatchRecord> getWatchRecordForChunk(
            ResourceKey<Level> dimension, int x, int z
    ) {
        var records = chunkWatchRecords.get(dimension);
        return records == null ? null : records.get(ChunkPos.asLong(x, z));
    }

    public static void forceRemovePlayer(ServerPlayer oldPlayer) {
        playerInfoMap.remove(oldPlayer);

        chunkWatchRecords.forEach((dim, dimMap) -> {
            dimMap.long2ObjectEntrySet().removeIf(e -> {
                long chunkPos = e.getLongKey();
                Object2ObjectOpenHashMap<ServerPlayer, PlayerWatchRecord> records = e.getValue();
                PlayerWatchRecord rec = records.remove(oldPlayer);
                if (rec != null) rec.isValid = false;
                if (rec != null && rec.isLoadedToPlayer && !isNativeChunkTracked(oldPlayer, dim,
                        ChunkPos.getX(chunkPos), ChunkPos.getZ(chunkPos))) {
                    PacketRedirection.sendRedirectedMessage(
                            oldPlayer, dim, new ClientboundForgetLevelChunkPacket(new ChunkPos(chunkPos))
                    );
                }

                return records.isEmpty();
            });
        });
    }

    public static boolean shouldLoadDimension(ResourceKey<Level> dimension) {
        if (!chunkWatchRecords.containsKey(dimension)) {
            return false;
        }
        var map =
                chunkWatchRecords.get(dimension);
        return !map.isEmpty();
    }

    public static void addGlobalAdditionalChunkLoader(
            MinecraftServer server,
            ChunkLoader chunkLoader
    ) {
        additionalChunkLoaders.add(chunkLoader);

        ResourceKey<Level> dimension = chunkLoader.dimension();
        ServerLevel world = server.getLevel(dimension);

        if (world == null) {
            LOGGER.error("Missing dimension in chunk loader {}", dimension.location());
            return;
        }

        RemoteChunkTickets dimTicketManager = RemoteChunkTickets.get(world);

        chunkLoader.foreachChunkPos((dim, x, z, distanceToSource) -> {
            dimTicketManager.markForLoading(ChunkPos.asLong(x, z), distanceToSource, generationCounter);
        });
    }

    /**
     * NOTE it removes chunk loader by object reference, not by value equality
     */
    public static void removeGlobalAdditionalChunkLoader(
            MinecraftServer server, ChunkLoader chunkLoader
    ) {
        additionalChunkLoaders.removeIf(c -> c == chunkLoader);
    }



    public static void addPerPlayerAdditionalChunkLoader(
            ServerPlayer player, ChunkLoader chunkLoader
    ) {
        PlayerChunkLoading playerInfo = getPlayerInfo(player);
        playerInfo.additionalChunkLoaders.add(chunkLoader);
        playerInfo.shouldUpdateImmediately = true;
    }

    /**
     * NOTE it removes chunk loader by object reference, not by value equality
     */
    public static void removePerPlayerAdditionalChunkLoader(
            ServerPlayer player, ChunkLoader chunkLoader
    ) {
        PlayerChunkLoading info = playerInfoMap.get(player);
        if (info == null) return;
        info.additionalChunkLoaders.removeIf(c -> c == chunkLoader);
        info.shouldUpdateImmediately = true;
    }

    public static Set<ResourceKey<Level>> getVisibleDimensions(ServerPlayer player) {
        PlayerChunkLoading info = playerInfoMap.get(player);
        return info == null ? Collections.emptySet() : info.visibleDimensions;
    }



}

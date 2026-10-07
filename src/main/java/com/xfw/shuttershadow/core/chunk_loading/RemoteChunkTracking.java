package com.xfw.shuttershadow.core.chunk_loading;
import com.xfw.shuttershadow.api.ChunkLoader;
import com.xfw.shuttershadow.ShuttershadowConfig;


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

/** 额外区块订阅核心。 */
public class RemoteChunkTracking {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int updateInterval = 13;

    /** 注册每服务端tick更新与ServerCleanup清静态集合。 */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            RemoteChunkTracking.tick(event.getServer());
        });

        NeoForge.EVENT_BUS.addListener(ServerCleanupEvent.class, event -> {
            MinecraftServer server = event.server;
            cleanup(server);
        });

    }

    // 玩家对象重建时，应传入旧玩家对象。
    /** 遍历全部世界清掉旧ServerPlayer实例的实体观察者，然后强制移除其远区块记录。 */
    public static void removePlayerFromChunkTrackersAndEntityTrackers(ServerPlayer oldPlayer) {
        for (ServerLevel world : oldPlayer.server.getAllLevels()) {
            ServerChunkCache chunkManager = world.getChunkSource();
            IEChunkMap storage =
                    (IEChunkMap) chunkManager.chunkMap;
            storage.ip_onPlayerUnload(oldPlayer);
        }

        forceRemovePlayer(oldPlayer);
    }

    /** 单玩家/维度/区块观察记录：代数、距离、已发送、边界及valid。 */
    public static class PlayerWatchRecord {
        public final ServerPlayer player;
        public final ResourceKey<Level> dimension;
        public final long chunkPos;
        public int lastWatchGeneration;
        public int distanceToSource;
        public boolean isLoadedToPlayer;
        public boolean isValid = true;
        public boolean isBoundary = false;
        // 仅在可见范围边界发送光照数据。
        // 范围内部的光照由客户端根据方块数据计算。

        /** 保存所有观察参数，isValid保留字段默认值。 */
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

        /** 生成带维度、区块坐标、距离、有效/发送状态的调试描述。 */
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

    // 每个区块维护其观察者记录列表。
    private static final Map<
            ResourceKey<Level>,
            Long2ObjectOpenHashMap<
                    Object2ObjectOpenHashMap<ServerPlayer, PlayerWatchRecord>>> chunkWatchRecords =
            new Object2ObjectOpenHashMap<>();

    private static final ArrayList<ChunkLoader> additionalChunkLoaders = new ArrayList<>();

    private static final Object2ObjectOpenHashMap<ServerPlayer, PlayerChunkLoading> playerInfoMap =
            new Object2ObjectOpenHashMap<>();

    private static int generationCounter = 0;

    /** 懒创建维度→区块→玩家观察记录表。 */
    private static Long2ObjectOpenHashMap<Object2ObjectOpenHashMap<ServerPlayer, PlayerWatchRecord>>
    getDimChunkWatchRecords(ResourceKey<Level> dimension) {
        return chunkWatchRecords.computeIfAbsent(dimension, k -> new Long2ObjectOpenHashMap<>());
    }

    /** 只有玩家实际所在维度且原版ChunkTrackingView包含坐标才为原版观察。 */
    public static boolean isNativeChunkTracked(ServerPlayer player, ResourceKey<Level> dimension, int x, int z) {
        return player.level().dimension() == dimension && player.getChunkTrackingView().contains(x, z);
    }

    /** 原版视距变化时，仅已有额外loader的玩家立即重新计算远窗口。 */
    public static void onNativeViewChanged(ServerPlayer player) {
        PlayerChunkLoading info = playerInfoMap.get(player);
        if (info != null && !info.additionalChunkLoaders.isEmpty()) updateForPlayer(player);
    }

    /** 按真实ServerPlayer身份建立PlayerChunkLoading，并检测其连接是否为内存连接。 */
    public static PlayerChunkLoading getPlayerInfo(ServerPlayer player) {
        return playerInfoMap.computeIfAbsent(
                player,
                (ServerPlayer p) -> new PlayerChunkLoading(
                        ((IEServerCommonPacketListenerImpl) p.connection)
                                .ip_getConnection().isMemoryConnection()
                )
        );
    }

    /** 立即重新计算窗口、尝试发送已可发送区块、再刷新全服实体观察者。 */
    public static void immediatelyUpdateForPlayer(ServerPlayer player) {
        RemoteChunkTracking.updateForPlayer(player);

        getPlayerInfo(player).doChunkSending(player);

        // 区块发送后再补充相机实体生成包。
        EntitySync.update(player.server);
    }

    /** 合并该玩家额外loader，登记可见维度与区块代数/距离/边界。 */
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
                            if (distanceToSource < oldDistance) {
                                record.distanceToSource = distanceToSource;
                                playerInfo.markPendingLoading(record);
                            }

                            record.isBoundary = (record.isBoundary && isBoundary);
                        } else {
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

    /** 过期观察记录发送必要的带维度forget并触发unwatch。 */
    private static void purge(
            MinecraftServer server,
            Object2ObjectOpenHashMap<ResourceKey<Level>, LongOpenHashSet> additionalLoadedChunks
    ) {
        // 清理失效的区块观察记录。
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

        // 清理失效的玩家加载信息。
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

    // 玩家加载区块较多时，提前释放不再观察的区块。
    /** 按服务端配置延迟卸载，区块较多时缩短至最多2代或1代。 */
    private static int getDelayUnloadGenerationForPlayer(ServerPlayer player) {
        int delayUnloadGenerations = ShuttershadowConfig.delayUnloadGenerations();
        int loadedChunks = getPlayerInfo(player).loadedChunks;

        if (loadedChunks > 2000) {
            return 1;
        }

        if (loadedChunks > 1200) {
            return Math.min(2, delayUnloadGenerations);
        }

        return delayUnloadGenerations;
    }

    /** 遍历全局loader标记需要的票据及保活集合，删除不存在维度的loader。 */
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

            chunkLoader.foreachChunkPos(new ChunkLoader.ChunkPosConsumer() /** 遍历全局loader区块的匿名ChunkPosConsumer。 */ {
                /** 把区块标入该世界票据队列并加入本代全局保活集合。 */
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

    /** 按玩家ID分散周期刷新，定期刷新全局loader并purge。 */
    private static void tick(MinecraftServer server) {
        server.getProfiler().push("shuttershadow_camera_tracking");

        long gameTime = server.overworld().getGameTime();
        for (var entry : playerInfoMap.entrySet()) {
            ServerPlayer player = entry.getKey();
            if (player.isRemoved()) continue;
            PlayerChunkLoading playerInfo = entry.getValue();

            // 将不同玩家的更新分散到不同游戏刻。
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

    /** 查指定玩家的区块记录，必须valid且已发送，最后应用额外谓词。 */
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

    /** 无额外条件查询valid且已发送的观察记录。 */
    public static boolean isPlayerWatchingChunk(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            int x, int z
    ) {
        return isPlayerWatchingChunk(player, dimension, x, z, r -> true);
    }

    /** 额外要求记录距离×16不超过给定方块半径。 */
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

    /** 清区块观察表、全局loader和玩家发送状态。 */
    private static void cleanup(MinecraftServer server) {
        chunkWatchRecords.clear();
        additionalChunkLoaders.clear();
        playerInfoMap.clear();
    }

    /** 从某区块有效已发送记录取观察者，可选择只取窗口边界观察者。 */
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

        // 仅发送光照更新包时启用只处理边界的筛选。
        // 客户端可计算内部光照，但加载边界需要服务端提供准确数据。

        ArrayList<ServerPlayer> result = new ArrayList<>();
        for (RemoteChunkTracking.PlayerWatchRecord rec : recs.values()) {
            if (rec.isValid && rec.isLoadedToPlayer && (!boundaryOnly || rec.isBoundary)) {
                result.add(rec.player);
            }
        }

        return result;
    }

    // 此处取得的观察记录均有效。
    /** 取得某维度区块的玩家记录表，无记录返回null。 */
    @Nullable
    public static Object2ObjectOpenHashMap<ServerPlayer, PlayerWatchRecord> getWatchRecordForChunk(
            ResourceKey<Level> dimension, int x, int z
    ) {
        var records = chunkWatchRecords.get(dimension);
        return records == null ? null : records.get(ChunkPos.asLong(x, z));
    }

    /** 移除玩家发送状态和全部远观察记录。 */
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

    /** 判断该维度是否还有额外观察区块记录。 */
    public static boolean shouldLoadDimension(ResourceKey<Level> dimension) {
        if (!chunkWatchRecords.containsKey(dimension)) {
            return false;
        }
        var map =
                chunkWatchRecords.get(dimension);
        return !map.isEmpty();
    }

    /** 添加全局loader并立即标记其全部区块加载需求。 */
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

    /** 按对象身份移除全局loader。 */
    public static void removeGlobalAdditionalChunkLoader(
            MinecraftServer server, ChunkLoader chunkLoader
    ) {
        additionalChunkLoaders.removeIf(c -> c == chunkLoader);
    }



    /** 添加玩家额外loader并要求下tick立即刷新。 */
    public static void addPerPlayerAdditionalChunkLoader(
            ServerPlayer player, ChunkLoader chunkLoader
    ) {
        PlayerChunkLoading playerInfo = getPlayerInfo(player);
        playerInfo.additionalChunkLoaders.add(chunkLoader);
        playerInfo.shouldUpdateImmediately = true;
    }

    /** 按身份删除玩家loader并要求立即刷新。 */
    public static void removePerPlayerAdditionalChunkLoader(
            ServerPlayer player, ChunkLoader chunkLoader
    ) {
        PlayerChunkLoading info = playerInfoMap.get(player);
        if (info == null) return;
        info.additionalChunkLoaders.removeIf(c -> c == chunkLoader);
        info.shouldUpdateImmediately = true;
    }

    /** 返回玩家当前额外可见维度集合，无状态为空集合。 */
    public static Set<ResourceKey<Level>> getVisibleDimensions(ServerPlayer player) {
        PlayerChunkLoading info = playerInfoMap.get(player);
        return info == null ? Collections.emptySet() : info.visibleDimensions;
    }



}

package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.ShuttershadowConfig;
import com.xfw.shuttershadow.RemoteCaptureContext;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.item.camera.Attachment;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.xfw.shuttershadow.util.McHelper;
import com.xfw.shuttershadow.api.ChunkLoading;
import com.xfw.shuttershadow.api.ChunkLoader;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A remote camera subscription. The real player stays in the source level;
 * Immersive Portals owns remote chunk loading, packet routing and entity
 * tracking. This class maps the camera position and owns its loader.
 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class RemoteCameraSession {
    private static final Map<UUID, RemoteCameraSession> ACTIVE = new HashMap<>();
    /** 截图订阅独立于取景器；不会替换玩家当前的常驻会话。 */
    private static final Map<Long, CaptureLoader> CAPTURES = new HashMap<>();
    private static final long CAPTURE_TIMEOUT_TICKS = 3600L;
    private static long nextCaptureSequence;

    private record CaptureLoader(ServerPlayer owner, ChunkLoader loader, ResourceKey<Level> sourceDimension,
                                 long lastUsedAt) {
        void release() {
            ChunkLoading.removeChunkLoaderForPlayer(owner, loader);
        }

        boolean unavailable() {
            return owner.isRemoved() || !owner.isAlive()
                    || owner.getServer().getPlayerList().getPlayer(owner.getUUID()) != owner
                    || owner.getServer().getLevel(loader.dimension()) == null
                    || owner.getServer().getLevel(sourceDimension) == null;
        }

        boolean expired(long lifetimeTicks) {
            return unavailable() || owner.getServer().overworld().getGameTime() - lastUsedAt >= lifetimeTicks;
        }

        boolean releaseIfExpired(long lifetimeTicks) {
            if (!expired(lifetimeTicks)) return false;
            release();
            return true;
        }
    }

    // IP removes loaders by identity. Retain the exact owner too, because
    // respawning can replace ServerPlayer while retaining the same UUID.
    private final ServerPlayer owner;
    private final long sequence;
    private final ResourceLocation filterId;
    private final ResourceKey<Level> sourceDimension;
    private final ServerLevel remoteLevel;
    private final double coordinateScale;
    private final int cameraStandId;
    private final Vec3 sourceOrigin;
    private final Vec3 targetOrigin;
    private final DimensionFilters.Route mapping;
    private ChunkLoader loader;
    private ChunkLoader sourceLoader;
    private int advertisedMaxRenderDistance = -1;

    private RemoteCameraSession(ServerPlayer player, CameraSessionRequestC2S request,
                                ServerLevel target, double coordinateScale, Vec3 initial,
                                Vec3 sourceOrigin, DimensionFilters.Route mapping) {
        owner = player;
        sequence = request.sequence();
        filterId = request.filterId();
        sourceDimension = player.level().dimension();
        remoteLevel = target;
        this.coordinateScale = coordinateScale;
        cameraStandId = request.cameraStandId();
        this.sourceOrigin = sourceOrigin;
        targetOrigin = initial;
        this.mapping = mapping;
    }

    public static void handle(CameraSessionRequestC2S request, ServerPlayer player) {
        if (request == null || request.filterId() == null || request.targetDimension() == null || !player.isAlive()) return;
        DimensionFilters.Route mapping = mappingFor(player, request.filterId(),
                request.targetDimension(), request.cameraStandId());
        if (mapping == null) return;
        ResourceLocation targetId = mapping.dimension();
        if (targetId == null) return;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, targetId);
        // A missing source route means this filter has no remote scene here.
        if (key.equals(player.level().dimension())) return;
        ServerLevel target = player.getServer().getLevel(key);
        if (target == null) return;

        RemoteCameraSession current = ACTIVE.get(player.getUUID());
        if (current != null && current.owner == player && current.sequence == request.sequence()
                && current.filterId.equals(request.filterId())
                && current.cameraStandId == request.cameraStandId()
                && current.sourceDimension.equals(player.level().dimension())
                && current.remoteLevel == target && current.matchesRoute(mapping) && current.isValid()) {
            current.sendScene(serverMaxRenderDistance(player));
            return;
        }

        if (current != null) close(current.owner);
        double scale = DimensionFilters.horizontalScale(mapping, player.serverLevel(), target);
        Vec3 sourceOrigin = cameraAnchorPosition(player, request.cameraStandId());
        if (sourceOrigin == null) return;
        Vec3 converted = DimensionFilters.mapAbsolute(sourceOrigin, scale);
        RemoteCameraSession created = new RemoteCameraSession(
                player, request, target, scale, converted, sourceOrigin, mapping);
        ACTIVE.put(player.getUUID(), created);
        // 首次建立与后续刷新共用入口：先注册 IP 订阅，再通知客户端场景与视距上限。
        created.refreshRemoteWindow();
    }

    /** Change the subscription only when the mapped camera crosses a chunk. */
    private void refreshRemoteWindow() {
        Vec3 mapped = targetCameraPosition(owner);
        BlockPos pos = BlockPos.containing(mapped);
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int maxRenderDistance = serverMaxRenderDistance(owner);
        // 玩家请求、服务器视距及相机专用上限共同限制真实远维度订阅。
        int effectiveRenderDistance = Math.min(McHelper.getPlayerLoadDistance(owner), maxRenderDistance);
        if (loader == null || loader.x() != chunkX || loader.z() != chunkZ
                || loader.radius() != effectiveRenderDistance) {
            ChunkLoader replacement = new ChunkLoader(remoteLevel.dimension(), chunkX, chunkZ,
                    effectiveRenderDistance);
            ChunkLoading.addChunkLoaderForPlayer(owner, replacement);
            if (loader != null) ChunkLoading.removeChunkLoaderForPlayer(owner, loader);
            loader = replacement;
        }

        // 支架预览也需要收到源世界的其它玩家；个人小视距不能截断合影名单。
        if (cameraStandId >= 0) {
            if (sourceLoader == null
                    || sourceLoader.radius() != (ShuttershadowConfig.standPlayerRadius() + 15) / 16) {
                ChunkLoader replacement = sourcePlayerLoader(sourceDimension, sourceOrigin);
                ChunkLoading.addChunkLoaderForPlayer(owner, replacement);
                if (sourceLoader != null) ChunkLoading.removeChunkLoaderForPlayer(owner, sourceLoader);
                sourceLoader = replacement;
            }
        }

        if (advertisedMaxRenderDistance != maxRenderDistance) {
            sendScene(maxRenderDistance);
        }
    }

    private void sendScene(int maxRenderDistance) {
        PacketDistributor.sendToPlayer(owner, new RemoteSceneStartS2C(
                sequence, remoteLevel.dimension().location(), targetOrigin,
                sourceOrigin, coordinateScale, maxRenderDistance, sourceDimension.location(),
                List.of()));
        advertisedMaxRenderDistance = maxRenderDistance;
    }

    private static DimensionFilters.Route mappingFor(ServerPlayer player, ResourceLocation filterId,
                                                          ResourceLocation targetDimension, int cameraStandId) {
        if (cameraStandId >= 0) {
            CameraStandEntity stand = cameraStand(player, cameraStandId);
            return stand == null ? null : mappingForCamera(player, stand.getCamera(), filterId, targetDimension);
        }
        // The client close packet is a convenience; the server camera state
        // remains authoritative if it is delayed or never arrives.
        DimensionFilters.Route mainHand =
                mappingForCamera(player, player.getMainHandItem(), filterId, targetDimension);
        return mainHand != null ? mainHand
                : mappingForCamera(player, player.getOffhandItem(), filterId, targetDimension);
    }

    private static DimensionFilters.Route mappingForCamera(ServerPlayer player,
                                                                ItemStack camera,
                                                                ResourceLocation filterId,
                                                                ResourceLocation targetDimension) {
        if (!(camera.getItem() instanceof CameraItem item) || !item.isActive(camera)) return null;
        ItemStack filter = Attachment.FILTER.get(camera).getForReading();
        if (filter.isEmpty() || !filterId.equals(BuiltInRegistries.ITEM.getKey(filter.getItem()))) {
            return null;
        }
        DimensionFilters.Route route = DimensionFilters.resolve(player.serverLevel().registryAccess(), filter,
                player.serverLevel().dimension().location());
        // 三种维度滤镜共用物品 ID，必须按目标组件解析出的路由区分。
        return route != null && targetDimension.equals(route.dimension()) ? route : null;
    }

    private static CameraStandEntity cameraStand(ServerPlayer player, int cameraStandId) {
        if (!(player.level().getEntity(cameraStandId) instanceof CameraStandEntity stand)) return null;
        if (!stand.isCameraActive()) return null;
        ServerPlayer executing = stand.getServerPlayerExecutingExposure().orElse(null);
        return executing != null && executing.getUUID().equals(player.getUUID()) ? stand : null;
    }

    private static Vec3 cameraAnchorPosition(ServerPlayer player, int cameraStandId) {
        CameraStandEntity stand = cameraStandId >= 0 ? cameraStand(player, cameraStandId) : null;
        return cameraStandId >= 0 ? (stand == null ? null : stand.position()) : player.position();
    }

    /** 取景器、支架照片与真实区块订阅共用服务端上限，沿用现有场景包同步。 */
    private static int serverMaxRenderDistance(ServerPlayer player) {
        return Math.max(1, Math.min(player.getServer().getPlayerList().getViewDistance(),
                ShuttershadowConfig.maxRemoteViewDistance()));
    }

    /** 截图订阅按独立序号管理，拍摄完成即解除，不保留闲置订阅。 */
    public static RemoteSceneStartS2C openCapture(ServerPlayer player, RemoteCaptureContext remote) {
        return createCapture(player, remote);
    }

    /** 手动远景与红石源照片共用负事务编号，避免完成回执匹配到另一张照片。 */
    static long allocateCaptureSequence() { return --nextCaptureSequence; }

    /** 源维度订阅只覆盖可出镜玩家的范围，实体状态由 IP 原生追踪同步。 */
    private static ChunkLoader sourcePlayerLoader(ResourceKey<Level> dimension, Vec3 origin) {
        BlockPos block = BlockPos.containing(origin);
        int radius = (ShuttershadowConfig.standPlayerRadius() + 15) / 16;
        return new ChunkLoader(dimension, block.getX() >> 4, block.getZ() >> 4, radius);
    }

    private static RemoteSceneStartS2C createCapture(ServerPlayer player, RemoteCaptureContext remote) {
        Vec3 position = remote.asHolderEntity().position();
        BlockPos block = BlockPos.containing(position);
        RemoteCameraSession preview = active(player.getUUID());
        // 正在观察的支架只保活同一个区块窗口，照片不另外扩大取景器的加载范围。
        int radius = preview != null && preview.cameraStandId == remote.cameraStandId()
                && preview.remoteLevel == remote.level() && preview.loader != null
                && preview.loader.x() == (block.getX() >> 4) && preview.loader.z() == (block.getZ() >> 4)
                ? preview.loader.radius() : McHelper.getPlayerLoadDistance(player);
        radius = Math.min(radius, serverMaxRenderDistance(player));
        ChunkLoader loader = new ChunkLoader(remote.level().dimension(),
                block.getX() >> 4, block.getZ() >> 4, radius);
        ChunkLoading.addChunkLoaderForPlayer(player, loader);
        long sequence = allocateCaptureSequence();
        CAPTURES.put(sequence, new CaptureLoader(player, loader, remote.source().asHolderEntity().level().dimension(),
                player.getServer().overworld().getGameTime()));
        return new RemoteSceneStartS2C(sequence, remote.level().dimension().location(), position,
                remote.source().asHolderEntity().position(), remote.coordinateScale(),
                serverMaxRenderDistance(player),
                remote.source().asHolderEntity().level().dimension().location(), List.of());
    }

    /** 照片沿用当前已同步的出镜玩家，不等待取景器之外的合影区块。 */
    public static List<ServerPlayer> syncedCapturePlayers(long sequence, List<ServerPlayer> players) {
        CaptureLoader capture = CAPTURES.get(sequence);
        if (capture == null) return List.of();
        return players.stream().filter(player -> {
            if (player == capture.owner()) return true;
            BlockPos position = player.blockPosition();
            int x = position.getX() >> 4;
            int z = position.getZ() >> 4;
            return RemoteChunkTracking.isPlayerWatchingChunk(capture.owner(), capture.sourceDimension(), x, z)
                    || RemoteChunkTracking.isNativeChunkTracked(capture.owner(), capture.sourceDimension(), x, z)
                    && !capture.owner().connection.chunkSender.isPending(
                            net.minecraft.world.level.ChunkPos.asLong(x, z));
        }).toList();
    }

    /** 当前已加载区块的实体生成包排在 CaptureStart 前，不等待其它区块。 */
    public static void flushCapture(ServerPlayer player) {
        RemoteChunkTracking.immediatelyUpdateForPlayer(player);
    }

    public static void close(ServerPlayer player, long sequence) {
        CaptureLoader capture = CAPTURES.get(sequence);
        if (capture != null && capture.owner() == player) {
            CAPTURES.remove(sequence);
            capture.release();
            return;
        }
        RemoteCameraSession current = ACTIVE.get(player.getUUID());
        if (current != null && current.sequence == sequence) close(player);
    }

    public static void close(ServerPlayer player) {
        RemoteCameraSession current = ACTIVE.get(player.getUUID());
        if (current != null && current.owner == player) {
            ACTIVE.remove(player.getUUID());
            current.releaseLoader();
        }
    }

    /**
     * Closes the server-side subscription before a physical dimension move.
     *
     * <p>The client stop packet is deliberately sent by
     * {@link #finishDimensionTeleport(ServerPlayer)} after Immersive Portals
     * has queued the dimension move. Sending it first makes Exposure restore
     * its stand camera while the client is still in the source world, which
     * produces the brief stand view and a second source-world reload.</p>
     */
    public static void closeBeforeDimensionTeleport(ServerPlayer player) {
        close(player);
    }

    /** Sends the client cleanup marker after the physical move was submitted. */
    public static void finishDimensionTeleport(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new RemoteSceneStopS2C());
    }

    private void releaseLoader() {
        if (loader != null) {
            ChunkLoading.removeChunkLoaderForPlayer(owner, loader);
            loader = null;
        }
        if (sourceLoader != null) {
            ChunkLoading.removeChunkLoaderForPlayer(owner, sourceLoader);
            sourceLoader = null;
        }
    }

    public static RemoteCameraSession active(UUID playerId) {
        RemoteCameraSession session = ACTIVE.get(playerId);
        return session != null && session.isValid() ? session : null;
    }
    public ServerLevel remoteLevel() { return remoteLevel; }
    public ResourceLocation filterId() { return filterId; }
    public boolean matchesRoute(DimensionFilters.Route current) { return mapping.equals(current); }
    public double coordinateScale() { return coordinateScale; }
    public int cameraStandId() { return cameraStandId; }
    public Vec3 sourceOrigin() { return sourceOrigin; }
    public Vec3 targetCameraPosition(ServerPlayer player) {
        return cameraStandId >= 0 ? targetOrigin : targetPosition(player);
    }
    public Vec3 targetPosition(ServerPlayer player) {
        Vec3 delta = player.position().subtract(sourceOrigin);
        return DimensionFilters.mapRelative(delta, targetOrigin, coordinateScale, 0.0D);
    }

    private boolean isValid() {
        if (owner.getServer().getPlayerList().getPlayer(owner.getUUID()) != owner
                || !owner.isAlive() || !owner.level().dimension().equals(sourceDimension)) return false;
        DimensionFilters.Route current = mappingFor(owner, filterId, mapping.dimension(), cameraStandId);
        return matchesRoute(current)
                && owner.getServer().getLevel(remoteLevel.dimension()) == remoteLevel;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        CAPTURES.entrySet().removeIf(entry -> !RemoteStandPreparation.ownsCapture(entry.getKey())
                && entry.getValue().releaseIfExpired(CAPTURE_TIMEOUT_TICKS));
        for (var iterator = ACTIVE.entrySet().iterator(); iterator.hasNext();) {
            RemoteCameraSession session = iterator.next().getValue();
            if (!session.isValid()) {
                iterator.remove();
                session.releaseLoader();
            } else {
                session.refreshRemoteWindow();
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            close(player);
            CAPTURES.values().removeIf(capture -> {
                if (capture.owner() != player) return false;
                capture.release();
                return true;
            });
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CAPTURES.values().forEach(CaptureLoader::release);
        CAPTURES.clear();
        for (RemoteCameraSession session : ACTIVE.values()) session.releaseLoader();
        ACTIVE.clear();
    }
}

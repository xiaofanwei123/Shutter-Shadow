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

/** 维护远维度取景会话、区块订阅和临时截图加载器。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class RemoteCameraSession {
    private static final Map<UUID, RemoteCameraSession> ACTIVE = new HashMap<>();
    /** 截图订阅独立于取景器；不会替换玩家当前的常驻会话。 */
    private static final Map<Long, CaptureLoader> CAPTURES = new HashMap<>();
    private static final long CAPTURE_TIMEOUT_TICKS = 3600L;
    private static long nextCaptureSequence;

    /** 记录照片区块加载器的所有者、来源维度和登记时间。 */
    private record CaptureLoader(ServerPlayer owner, ChunkLoader loader, ResourceKey<Level> sourceDimension,
                                 long lastUsedAt) {
        /** 移除该照片的玩家区块加载器。 */
        void release() {
            ChunkLoading.removeChunkLoaderForPlayer(owner, loader);
        }

        /** 摄影师/世界不可用或照片订阅超期时释放，供removeIf移除。 */
        boolean releaseIfExpired(long lifetimeTicks) {
            boolean expired = owner.isRemoved() || !owner.isAlive()
                    || owner.getServer().getPlayerList().getPlayer(owner.getUUID()) != owner
                    || owner.getServer().getLevel(loader.dimension()) == null
                    || owner.getServer().getLevel(sourceDimension) == null
                    || owner.getServer().overworld().getGameTime() - lastUsedAt >= lifetimeTicks;
            if (!expired) return false;
            release();
            return true;
        }
    }

    // 加载器按对象身份移除，也要保留确切的所有者实例，
    // 因为玩家重生后唯一标识不变，但服务端玩家实例已替换。
    private final ServerPlayer owner;
    private final long sequence;
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

    /** 保存摄影师、会话序号、路由、来源世界、目标世界、坐标映射与支架ID，loader在刷新时建立。 */
    private RemoteCameraSession(ServerPlayer player, CameraSessionRequestC2S request,
                                ServerLevel target, double coordinateScale, Vec3 initial,
                                Vec3 sourceOrigin, DimensionFilters.Route mapping) {
        owner = player;
        sequence = request.sequence();
        sourceDimension = player.level().dimension();
        remoteLevel = target;
        this.coordinateScale = coordinateScale;
        cameraStandId = request.cameraStandId();
        this.sourceOrigin = sourceOrigin;
        targetOrigin = initial;
        this.mapping = mapping;
    }

    /** 验证请求、实际活动相机、滤镜目标组件、支架控制者和路由。 */
    public static void handle(CameraSessionRequestC2S request, ServerPlayer player) {
        if (request == null || request.filterId() == null || request.targetDimension() == null || !player.isAlive()) return;
        DimensionFilters.Route mapping = mappingFor(player, request.filterId(),
                request.targetDimension(), request.cameraStandId());
        if (mapping == null) return;
        ResourceLocation targetId = mapping.dimension();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, targetId);
        ServerLevel target = player.getServer().getLevel(key);
        if (target == null) return;

        RemoteCameraSession current = ACTIVE.get(player.getUUID());
        if (current != null && current.owner == player && current.sequence == request.sequence()
                && current.cameraStandId == request.cameraStandId()
                && current.sourceDimension.equals(player.level().dimension())
                && current.remoteLevel == target && current.matchesRoute(mapping)
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player) {
            current.sendScene(serverMaxRenderDistance(player));
            return;
        }

        if (current != null) close(current.owner);
        double scale = DimensionFilters.horizontalScale(player.serverLevel(), target);
        Vec3 sourceOrigin = cameraAnchorPosition(player, request.cameraStandId());
        if (sourceOrigin == null) return;
        Vec3 converted = DimensionFilters.mapAbsolute(sourceOrigin, scale);
        RemoteCameraSession created = new RemoteCameraSession(
                player, request, target, scale, converted, sourceOrigin, mapping);
        ACTIVE.put(player.getUUID(), created);
        // 首次建立与后续刷新共用入口：先注册 IP 订阅，再通知客户端场景与视距上限。
        created.refreshRemoteWindow();
    }

    /** 随镜头位置刷新远维度与来源玩家的区块订阅。 */
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

    /** 发送当前目标/来源原点、比例、上限和来源维度的场景包并记已公布上限。 */
    private void sendScene(int maxRenderDistance) {
        PacketDistributor.sendToPlayer(owner, new RemoteSceneStartS2C(
                sequence, remoteLevel.dimension().location(), targetOrigin,
                sourceOrigin, coordinateScale, maxRenderDistance, sourceDimension.location()));
        advertisedMaxRenderDistance = maxRenderDistance;
    }

    /** 从手持或支架相机校验请求并解析滤镜路由。 */
    private static DimensionFilters.Route mappingFor(ServerPlayer player, ResourceLocation filterId,
                                                          ResourceLocation targetDimension, int cameraStandId) {
        if (cameraStandId >= 0) {
            CameraStandEntity stand = cameraStand(player, cameraStandId);
            return stand == null ? null : mappingForCamera(player, stand.getCamera(), filterId, targetDimension);
        }
        // 客户端关闭通知仅用于及时清理，
        // 延迟或未到达时仍以服务端相机状态为准。
        DimensionFilters.Route mainHand =
                mappingForCamera(player, player.getMainHandItem(), filterId, targetDimension);
        return mainHand != null ? mainHand
                : mappingForCamera(player, player.getOffhandItem(), filterId, targetDimension);
    }

    /** 确认CameraItem处于活动状态、附件物品ID与客户端一致，重新解析来源路由并验证其组件目标与请求目标一致。 */
    private static DimensionFilters.Route mappingForCamera(ServerPlayer player,
                                                                ItemStack camera,
                                                                ResourceLocation filterId,
                                                                ResourceLocation targetDimension) {
        if (!(camera.getItem() instanceof CameraItem item) || !item.isActive(camera)) return null;
        ItemStack filter = Attachment.FILTER.get(camera).getForReading();
        if (filter.isEmpty() || !filterId.equals(BuiltInRegistries.ITEM.getKey(filter.getItem()))) {
            return null;
        }
        DimensionFilters.Route route = DimensionFilters.resolve(filter,
                player.serverLevel().dimension().location());
        // 三种维度滤镜共用物品 ID，必须按目标组件解析出的路由区分。
        return route != null && targetDimension.equals(route.dimension()) ? route : null;
    }

    /** 确认当前世界对应实体是活动支架，并且Exposure当前执行玩家就是请求者。 */
    private static CameraStandEntity cameraStand(ServerPlayer player, int cameraStandId) {
        if (!(player.level().getEntity(cameraStandId) instanceof CameraStandEntity stand)) return null;
        if (!stand.isCameraActive()) return null;
        ServerPlayer executing = stand.getServerPlayerExecutingExposure().orElse(null);
        return executing != null && executing.getUUID().equals(player.getUUID()) ? stand : null;
    }

    /** 返回支架位置或手持玩家位置作为来源锚点。 */
    private static Vec3 cameraAnchorPosition(ServerPlayer player, int cameraStandId) {
        CameraStandEntity stand = cameraStandId >= 0 ? cameraStand(player, cameraStandId) : null;
        return cameraStandId >= 0 ? (stand == null ? null : stand.position()) : player.position();
    }

    /** 返回服务器原版视距与相机配置上限的最小值，并至少为1。 */
    private static int serverMaxRenderDistance(ServerPlayer player) {
        return Math.max(1, Math.min(player.getServer().getPlayerList().getViewDistance(),
                ShuttershadowConfig.maxRemoteViewDistance()));
    }

    /** 分配持续递减的负照片序号，与客户端正预览序号区分。 */
    static long allocateCaptureSequence() { return --nextCaptureSequence; }

    /** 按支架玩家半径向上换算区块半径，建立源维度玩家同步loader。 */
    private static ChunkLoader sourcePlayerLoader(ResourceKey<Level> dimension, Vec3 origin) {
        BlockPos block = BlockPos.containing(origin);
        int radius = (ShuttershadowConfig.standPlayerRadius() + 15) / 16;
        return new ChunkLoader(dimension, block.getX() >> 4, block.getZ() >> 4, radius);
    }

    /** 创建独立照片订阅，沿用匹配预览半径并钳制服务器上限。 */
    public static RemoteSceneStartS2C openCapture(ServerPlayer player, RemoteCaptureContext remote) {
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
                remote.source().asHolderEntity().level().dimension().location());
    }

    /** 只保留摄影师本人或其源区块已在远同步/原版已发送状态的候选玩家，避免等待照片视角之外的区块。 */
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

    /** 立即刷新摄影师的远区块/实体跟踪，保证已有实体包排在截图请求前。 */
    public static void flushCapture(ServerPlayer player) {
        RemoteChunkTracking.immediatelyUpdateForPlayer(player);
    }

    /** 按玩家身份与序号先关闭照片订阅，否则关闭匹配的预览会话。 */
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

    /** 只删除属于同一ServerPlayer实例的活动会话并释放loader。 */
    public static void close(ServerPlayer player) {
        RemoteCameraSession current = ACTIVE.get(player.getUUID());
        if (current != null && current.owner == player) {
            ACTIVE.remove(player.getUUID());
            current.releaseLoader();
        }
    }

    /** 真实移动包排入连接之后才发RemoteSceneStop，避免支架视角在旧世界短暂恢复。 */
    public static void finishDimensionTeleport(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new RemoteSceneStopS2C());
    }

    /** 移除目标与源玩家同步loader并置null。 */
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

    /** 查UUID并再次验证会话，失效返回null。 */
    public static RemoteCameraSession active(UUID playerId) {
        RemoteCameraSession session = ACTIVE.get(playerId);
        return session != null && session.isValid() ? session : null;
    }
    /** 返回目标世界。 */
    public ServerLevel remoteLevel() { return remoteLevel; }
    /** 比较完整路由记录，防止同物品ID的不同目标滤镜复用旧场景。 */
    public boolean matchesRoute(DimensionFilters.Route current) { return mapping.equals(current); }
    /** 返回水平映射比例。 */
    public double coordinateScale() { return coordinateScale; }
    /** 返回支架ID。 */
    public int cameraStandId() { return cameraStandId; }
    /** 返回固定来源原点。 */
    public Vec3 sourceOrigin() { return sourceOrigin; }
    /** 支架返回固定目标锚点，手持计算跟随玩家的目标位置。 */
    public Vec3 targetCameraPosition(ServerPlayer player) {
        return cameraStandId >= 0 ? targetOrigin : targetPosition(player);
    }
    /** 把玩家相对源原点偏移映射到目标原点。 */
    public Vec3 targetPosition(ServerPlayer player) {
        Vec3 delta = player.position().subtract(sourceOrigin);
        return DimensionFilters.mapRelative(delta, targetOrigin, coordinateScale, 0.0D);
    }

    /** 确认在线玩家实例、存活、来源世界不变、当前活动相机路由匹配且目标世界仍存在。 */
    private boolean isValid() {
        if (owner.getServer().getPlayerList().getPlayer(owner.getUUID()) != owner
                || !owner.isAlive() || !owner.level().dimension().equals(sourceDimension)) return false;
        DimensionFilters.Route current = mappingFor(owner, mapping.filter(), mapping.dimension(), cameraStandId);
        return matchesRoute(current)
                && owner.getServer().getLevel(remoteLevel.dimension()) == remoteLevel;
    }

    /** 清理无人拥有且超期的照片loader。 */
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

    /** 登出时关闭该实例预览并释放该实例拥有的照片loader。 */
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

    /** 服务端停止时释放所有loader并清空静态集合。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CAPTURES.values().forEach(CaptureLoader::release);
        CAPTURES.clear();
        for (RemoteCameraSession session : ACTIVE.values()) session.releaseLoader();
        ACTIVE.clear();
    }
}

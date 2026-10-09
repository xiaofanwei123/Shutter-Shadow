package com.xfw.dimensionalexposure.camera;

import com.xfw.dimensionalexposure.DimensionalExposure;
import com.xfw.dimensionalexposure.DimensionalExposureConfig;
import com.xfw.dimensionalexposure.network.RemoteStandPreparation;
import com.xfw.dimensionalexposure.network.RemoteCameraSession;
import com.xfw.dimensionalexposure.network.RemoteSceneStartS2C;
import com.xfw.dimensionalexposure.util.CaptureEntitySearchRange;
import io.github.mortuusars.exposure.util.PointOfView;
import io.github.mortuusars.exposure.world.camera.frame.EntitiesInFrame;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 连接默认拍摄、异步上传和胶卷传送的内部事务。 */
@EventBusSubscriber(modid = DimensionalExposure.MODID)
public final class CameraCaptureTransactions {
    private static final Map<ItemStack, Shot> SHOTS = new IdentityHashMap<>();
    private static final Map<CaptureSnapshot, Shot> ALL = new IdentityHashMap<>();
    private static final Map<ServerPlayer, Map<String, Shot>> IMAGE_SHOTS = new IdentityHashMap<>();

    private static final class Shot {
        final CaptureSnapshot context;
        final ItemStack camera;
        final RemoteCaptureContext remote;
        final boolean discardImage;
        final boolean playerTransfer;
        final boolean mobTransfer;
        final int mobRadius;
        final int safetyLevel;
        ServerPlayer photographedPlayer;
        Vec3 playerDestination;
        RemoteSceneStartS2C captureScene;
        String exposureId;
        boolean nativeReturned;
        boolean uploaded;
        boolean standHeld;
        int ticks;
        QueryKey photoQuery;
        List<LivingEntity> photoCandidates = List.of();

        /** 受理时固定附件行为与观察锚点，后续照片不能覆盖旧上传。 */
        Shot(CaptureSnapshot context, ItemStack camera, @Nullable RemoteCaptureContext remote) {
            this.context = context;
            this.camera = camera;
            this.remote = remote;
            discardImage = CameraEnchantments.has(camera, CameraEnchantments.EXPOSURE_FAILURE);
            playerTransfer = remote != null && DimensionFilmCapture.hasPlayerDimensionFilm(camera);
            mobTransfer = remote != null && MobDimensionFilmCapture.hasMobDimensionFilm(camera);
            mobRadius = DimensionalExposureConfig.mobCaptureRadius();
            safetyLevel = CameraEnchantments.level(camera, CameraEnchantments.SAFE_DIMENSION_TELEPORT);
        }
    }

    /** 只有镜头、视锥和范围相同，才复用照片的原始实体查询。 */
    private record QueryKey(CameraHolder holder, PointOfView view, double fov, Integer radius) {}

    private CameraCaptureTransactions() {}

    /** 首次固定拍摄，支架准备复用同一事务。 */
    public static boolean begin(CameraItem item, CameraHolder holder, ServerPlayer player, ItemStack camera) {
        Shot pending = SHOTS.get(camera);
        if (pending != null) return !pending.nativeReturned && pending.context.getExecutor() == player
                && pending.context.getHolder().asHolderEntity() == holder.asHolderEntity();
        boolean stand = holder.asHolderEntity() instanceof CameraStandEntity;
        boolean redstone = stand && RemoteStandPreparation.isRedstoneCapture((CameraStandEntity) holder.asHolderEntity());
        RemoteCaptureContext remote = RemoteCaptureContext.resolveForTransfer(holder, camera);
        CaptureSnapshot context = new CaptureSnapshot(redstone ? CaptureSnapshot.Trigger.REDSTONE
                : stand ? CaptureSnapshot.Trigger.MANUAL_STAND : CaptureSnapshot.Trigger.HANDHELD,
                holder, player, !stand && item.isInSelfieMode(camera),
                remote == null ? null : remote.level().dimension().location());
        Shot shot = new Shot(context, camera, remote);
        SHOTS.put(camera, shot);
        ALL.put(context, shot);
        return true;
    }

    public static @Nullable CaptureSnapshot context(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        return shot == null ? null : shot.context;
    }

    public static boolean discardsImage(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        return shot == null ? CameraEnchantments.has(camera, CameraEnchantments.EXPOSURE_FAILURE) : shot.discardImage;
    }

    public static boolean transfersPlayers(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        return shot != null && shot.playerTransfer;
    }

    public static boolean transfersMobs(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        return shot != null && shot.mobTransfer;
    }

    public static int mobRadius(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        return shot == null ? DimensionalExposureConfig.mobCaptureRadius() : shot.mobRadius;
    }

    /** 异步传送只接受仍存活的原照片，不读取相机后续建立的事务。 */
    public static boolean isActive(@Nullable CaptureSnapshot context) {
        return context != null && ALL.containsKey(context);
    }

    /** 图片编号绑定真实玩家实例，重连后的同 UUID 玩家不能结束旧事务。 */
    public static void registerExposure(ItemStack camera, String id) {
        Shot shot = SHOTS.get(camera);
        if (shot == null || ALL.get(shot.context) != shot || Objects.equals(shot.exposureId, id)) return;
        removeImageIndex(shot);
        shot.exposureId = id;
        IMAGE_SHOTS.computeIfAbsent(shot.context.getExecutor(), ignored -> new HashMap<>()).put(id, shot);
    }

    private static @Nullable Shot findImage(ServerPlayer player, String id) {
        Map<String, Shot> shots = IMAGE_SHOTS.get(player);
        return shots == null ? null : shots.get(id);
    }

    /** 条件移除旧记录，不影响同一相机或编号后续建立的事务。 */
    private static void removeImageIndex(Shot shot) {
        if (shot.exposureId == null) return;
        ServerPlayer player = shot.context.getExecutor();
        Map<String, Shot> shots = IMAGE_SHOTS.get(player);
        if (shots != null && shots.remove(shot.exposureId, shot) && shots.isEmpty()) IMAGE_SHOTS.remove(player, shots);
    }

    /** 红石照片来自源世界，其余维度相机采用目标世界。 */
    public static @Nullable RemoteCaptureContext photoContext(CameraHolder holder, ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        if (shot == null) return RemoteCaptureContext.resolve(holder, camera);
        return shot.context.getTrigger() == CaptureSnapshot.Trigger.REDSTONE || shot.remote == null
                || shot.remote.level() == holder.asHolderEntity().level() ? null : shot.remote;
    }

    public static @Nullable RemoteCaptureContext playerContext(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        return shot == null || !shot.playerTransfer ? null : shot.remote;
    }

    public static @Nullable RemoteCaptureContext mobContext(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        return shot == null || !shot.mobTransfer ? null : shot.remote;
    }

    /** 保存原始查询供生物胶卷复用，照片保留存活的真实实体。 */
    public static List<LivingEntity> photoSubjects(CameraHolder holder, ItemStack camera, PointOfView view,
            double fov, @Nullable Integer radius, List<LivingEntity> candidates) {
        Shot shot = SHOTS.get(camera);
        if (shot == null) return candidates;
        if (shot.mobTransfer) {
            shot.photoQuery = new QueryKey(holder, view, fov, radius);
            shot.photoCandidates = List.copyOf(candidates);
        }
        return candidates.stream().filter(entity -> entity.isAlive() && !entity.isRemoved())
                .toList();
    }

    /** 支架名单保留玩家个人接受选项及在线身份、存活校验。 */
    public static List<ServerPlayer> selectPlayers(ItemStack camera, List<ServerPlayer> candidates) {
        Shot shot = SHOTS.get(camera);
        if (shot == null || !shot.playerTransfer) return List.of();
        return candidates.stream().filter(player -> player.isAlive() && !player.isRemoved())
                .filter(player -> player.getServer().getPlayerList().getPlayer(player.getUUID()) == player)
                .filter(player -> DimensionFilmCapture.acceptsTeleport(shot.context, player)).toList();
    }

    /** 独立截图场景不替换取景会话；名单只包含已同步给摄影师的来源玩家。 */
    public static RemoteSceneStartS2C openHandheldCapture(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        if (shot == null || shot.remote == null || shot.context.getTrigger() != CaptureSnapshot.Trigger.HANDHELD)
            throw new IllegalStateException("Missing handheld remote capture");
        if (shot.captureScene == null) {
            shot.captureScene = RemoteCameraSession.openCapture(shot.context.getExecutor(), shot.remote);
            RemoteCameraSession.flushCapture(shot.context.getExecutor());
            List<ServerPlayer> players = RemoteCameraSession.syncedCapturePlayers(
                    shot.captureScene.sequence(), shot.remote.playersInFrame(camera));
            shot.remote.freezePlayersInFrame(players);
            selectHandheldPlayer(shot);
        }
        return shot.captureScene;
    }

    /** 按默认规则选择首个存活的非玩家生物，不再提供名单修改扩展。 */
    public static @Nullable LivingEntity selectMob(ItemStack camera, RemoteCaptureContext remote) {
        Shot shot = SHOTS.get(camera);
        if (shot == null || !shot.mobTransfer || !(camera.getItem() instanceof CameraItem item)) return null;
        PointOfView view = item.getPointOfView(remote, camera);
        double fov = item.getViewfinderFov(remote.level(), camera);
        int radius = shot.mobRadius;
        List<LivingEntity> raw = new QueryKey(remote, view, fov, radius).equals(shot.photoQuery)
                ? shot.photoCandidates : CaptureEntitySearchRange.withRadius(radius,
                () -> EntitiesInFrame.get(remote, view, fov));
        return raw.stream().filter(entity -> !(entity instanceof Player) && entity.isAlive() && !entity.isRemoved())
                .findFirst().orElse(null);
    }

    /** 帧生成后登记上传编号并准备生物事务，免成片仍执行传送。 */
    public static void prepareFrame(ItemStack camera, Frame frame) {
        Shot shot = SHOTS.get(camera);
        if (shot == null) return;
        try {
            registerExposure(camera, frame.identifier().id());
            if (shot.remote != null && shot.context.getTrigger() == CaptureSnapshot.Trigger.HANDHELD) {
                if (shot.discardImage) selectHandheldPlayer(shot);
                else openHandheldCapture(camera);
            }
            if (shot.mobTransfer) MobDimensionFilmCapture.prepare(camera, frame, shot.context);
        } finally {
            // 帧元数据和生物目标已固定，上传等待期间不再持有原始查询实体。
            shot.photoQuery = null;
            shot.photoCandidates = List.of();
        }
    }

    /** 先固定首位出镜玩家，再读取接受设置；拒绝时不能改选后面的玩家。 */
    private static void selectHandheldPlayer(Shot shot) {
        if (!shot.playerTransfer || shot.context.getTrigger() != CaptureSnapshot.Trigger.HANDHELD) return;
        shot.photographedPlayer = shot.remote.playersInFrame(shot.camera).stream().findFirst().orElse(null);
        shot.playerDestination = shot.photographedPlayer == null ? null : shot.remote.targetPosition(shot.photographedPlayer);
    }

    public static void holdStand(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        if (shot != null) shot.standHeld = true;
    }

    /** 支架出片和传送完成后，解除原生返回与上传的共同等待。 */
    public static void standFinished(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        if (shot != null) { shot.standHeld = false; SHOTS.remove(camera, shot); completeIfReady(shot); }
    }

    public static void nativeReturned(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        if (shot != null) {
            shot.nativeReturned = true;
            if (!shot.standHeld) SHOTS.remove(camera, shot);
            completeIfReady(shot);
        }
    }

    public static void uploaded(ServerPlayer player, String id) {
        Shot shot = findImage(player, id);
        if (shot != null) { shot.uploaded = true; completeIfReady(shot); }
    }

    public static void imageFailed(ServerPlayer player, String id) {
        Shot shot = findImage(player, id);
        if (shot != null) finish(shot, "客户端图片生成或处理失败");
    }

    /** 异步失败仅终结原拍摄，不清掉相机随后建立的新事务。 */
    public static void failed(@Nullable CaptureSnapshot context, String reason) {
        Shot shot = context == null ? null : ALL.get(context);
        if (shot != null) finish(shot, reason);
    }

    private static void completeIfReady(Shot shot) {
        if (shot.nativeReturned && !shot.standHeld && (shot.discardImage || shot.uploaded)) finish(shot, null);
    }

    /** 先移除索引再清理内部待办，避免旧回调或重入重复收尾。 */
    private static void finish(Shot shot, @Nullable String failure) {
        if (!ALL.remove(shot.context, shot)) return;
        SHOTS.remove(shot.camera, shot);
        removeImageIndex(shot);
        if (failure != null) {
            DimensionalExposure.LOGGER.debug("相机拍摄结束：{}", failure);
            RemoteStandPreparation.cancelCapture(shot.context, shot.camera);
            if (shot.exposureId != null) {
                MobDimensionFilmCapture.cancelPending(shot.context.getExecutor(), shot.exposureId);
                ((RemoteStandPreparation.UploadWindow) io.github.mortuusars.exposure.ExposureServer.exposureRepository())
                        .dimensionalExposure$cancelExpected(shot.context.getExecutor(), shot.exposureId);
            }
        }
        if (shot.captureScene != null)
            RemoteCameraSession.close(shot.context.getExecutor(), shot.captureScene.sequence());
        if (failure == null && shot.photographedPlayer != null) {
            DimensionFilmCapture.teleportHandheldPlayerAfterPhoto(shot.context, shot.remote,
                    shot.safetyLevel, shot.photographedPlayer, shot.playerDestination);
        }
    }

    /** 使用服务端 tick 限时，死亡、断线和超时均释放原拍摄。 */
    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        for (Shot shot : List.copyOf(ALL.values())) {
            ServerPlayer player = shot.context.getExecutor();
            if (!player.isAlive() || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                    || ++shot.ticks > 4800) finish(shot, "执行者失效或拍摄超时");
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        for (Shot shot : List.copyOf(ALL.values())) {
            if (shot.context.getExecutor() == event.getEntity()) finish(shot, "执行者退出");
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent event) {
        for (Shot shot : List.copyOf(ALL.values())) finish(shot, "服务器关闭");
    }
}

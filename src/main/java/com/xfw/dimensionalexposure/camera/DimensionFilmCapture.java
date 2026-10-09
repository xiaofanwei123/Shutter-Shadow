package com.xfw.dimensionalexposure.camera;

import com.xfw.dimensionalexposure.DimensionalExposure;
import com.xfw.dimensionalexposure.api.SeamlessTeleportation;
import com.xfw.dimensionalexposure.item.PlayerDimensionFilmRollItem;
import com.xfw.dimensionalexposure.network.RemoteCameraSession;
import io.github.mortuusars.exposure.world.camera.CameraOnStand;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.item.camera.Attachment;
import net.minecraft.resources.ResourceKey;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 成片后执行玩家维度胶卷传送，并维护个人接受设置和传送保护。 */
@EventBusSubscriber(modid = DimensionalExposure.MODID)
public final class DimensionFilmCapture {
    private static final int PORTAL_TRANSFER_LOCK_TICKS = 200;
    private static final Map<UUID, Integer> PORTAL_TRANSFER_LOCKS = new HashMap<>();
    private static final Map<UUID, Preferences> PREFERENCES = new HashMap<>();
    private static final Set<UUID> EXPLICIT_TRANSFERS = new HashSet<>();
    private record Preferences(boolean stand, boolean otherPlayers) {}
    private static final Preferences DEFAULT_PREFERENCES = new Preferences(true, true);

    private DimensionFilmCapture() {}

    /** 支架准备完成后调用 Exposure 的原生拍摄入口。 */
    public interface TakePhotoInvoker {
        void dimensionalExposure$invokeTakePhoto(CameraHolder holder, ServerPlayer player, ItemStack camera);
    }

    public static void setTeleportPreferences(ServerPlayer player, boolean acceptStand, boolean acceptOtherPlayers) {
        PREFERENCES.put(player.getUUID(), new Preferences(acceptStand, acceptOtherPlayers));
    }

    /** 自己拍自己不受“接受其他玩家”限制，红石保留原有支架规则。 */
    public static boolean acceptsTeleport(CaptureSnapshot context, ServerPlayer player) {
        Preferences preferences = PREFERENCES.getOrDefault(player.getUUID(), DEFAULT_PREFERENCES);
        if (context.getTrigger() != CaptureSnapshot.Trigger.HANDHELD && !preferences.stand()) return false;
        return context.getTrigger() == CaptureSnapshot.Trigger.REDSTONE
                || context.getExecutor() == player || preferences.otherPlayers();
    }

    /** 使用曝光时冻结的首位玩家和目的地，拒绝时不改选下一名玩家。 */
    public static void teleportHandheldPlayerAfterPhoto(CaptureSnapshot context, RemoteCaptureContext remote,
            int safetyLevel, ServerPlayer player, Vec3 destination) {
        if (context.getTrigger() == CaptureSnapshot.Trigger.HANDHELD
                && eligible(context, player) && acceptsTeleport(context, player)
                && player.getServer().getLevel(remote.level().dimension()) == remote.level()) {
            RemoteCameraSession sourceSession = RemoteCameraSession.active(player.getUUID());
            teleport(player, remote.level(), destination, safetyLevel,
                    () -> RemoteCameraSession.finishDimensionTeleport(player, sourceSession));
        }
    }

    public static void teleportStandPlayersAfterPhoto(CameraHolder holder, ItemStack camera) {
        if (!(holder.asHolderEntity() instanceof CameraStandEntity)
                || !CameraCaptureTransactions.transfersPlayers(camera)) return;
        RemoteCaptureContext remote = CameraCaptureTransactions.playerContext(camera);
        if (remote != null) teleportStandPlayersAfterPhoto(remote, camera,
                CameraCaptureTransactions.selectPlayers(camera, remote.playersInFrame(camera)));
    }

    /** 支架继续传送冻结名单中的玩家，成功后才清理来源支架控制。 */
    public static void teleportStandPlayersAfterPhoto(RemoteCaptureContext remote, ItemStack camera,
                                                      List<ServerPlayer> players) {
        CaptureSnapshot context = CameraCaptureTransactions.context(camera);
        if (context == null || !CameraCaptureTransactions.transfersPlayers(camera)) return;
        for (ServerPlayer player : players) {
            if (!eligible(context, player) || !acceptsTeleport(context, player)) continue;
            CameraOperator operator = (CameraOperator) player;
            var activeCamera = operator.getActiveExposureCamera();
            CameraStandEntity controlledStand = activeCamera instanceof CameraOnStand onStand
                    && onStand.getOperator() == operator && onStand.getStand() == remote.source().asHolderEntity()
                    ? onStand.getStand() : null;
            ItemStack controlledCamera = controlledStand == null ? ItemStack.EMPTY : controlledStand.getCamera();
            RemoteCameraSession sourceSession = RemoteCameraSession.active(player.getUUID());
            teleport(player, remote.level(), remote.targetPosition(player),
                    CameraEnchantments.level(camera, CameraEnchantments.SAFE_DIMENSION_TELEPORT), () -> {
                try {
                    if (controlledStand != null && operator.getActiveExposureCamera() == activeCamera)
                        operator.removeActiveExposureCamera();
                    if (controlledStand != null && controlledStand.getCamera() == controlledCamera
                            && controlledStand.getOperatorId() == player.getId()) controlledStand.stopControlling();
                } finally {
                    RemoteCameraSession.finishDimensionTeleport(player, sourceSession);
                }
            });
        }
    }

    private static boolean eligible(CaptureSnapshot context, ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && player.level() == context.getSourceLevel()
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private static boolean teleport(ServerPlayer player, ServerLevel target, Vec3 targetPosition,
                                    int safetyLevel, Runnable afterMove) {
        ResourceKey<Level> sourceDimension = player.level().dimension();
        UUID uuid = player.getUUID();
        PORTAL_TRANSFER_LOCKS.put(uuid, PORTAL_TRANSFER_LOCK_TICKS);
        EXPLICIT_TRANSFERS.add(uuid);
        try {
            ServerPlayer moved = SeamlessTeleportation.teleportPlayer(player, target, targetPosition);
            if (moved == null) { PORTAL_TRANSFER_LOCKS.remove(uuid); return false; }
            afterMove.run();
            if (!sourceDimension.equals(player.level().dimension())) CameraTeleportSafetyEffect.apply(player, safetyLevel);
            return true;
        } finally {
            EXPLICIT_TRANSFERS.remove(uuid);
        }
    }

    public static boolean shouldBlockPortalTeleport(ServerPlayer player) {
        return PORTAL_TRANSFER_LOCKS.containsKey(player.getUUID());
    }

    public static boolean isExplicitTransferInProgress(ServerPlayer player) {
        return EXPLICIT_TRANSFERS.contains(player.getUUID());
    }

    public static boolean hasPlayerDimensionFilm(ItemStack camera) {
        return Attachment.FILM.get(camera).getForReading().getItem() instanceof PlayerDimensionFilmRollItem
                && !Attachment.FILTER.get(camera).getForReading().isEmpty();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        PORTAL_TRANSFER_LOCKS.replaceAll((uuid, ticks) -> ticks - 1);
        PORTAL_TRANSFER_LOCKS.values().removeIf(ticks -> ticks <= 0);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PORTAL_TRANSFER_LOCKS.remove(player.getUUID());
            EXPLICIT_TRANSFERS.remove(player.getUUID());
            PREFERENCES.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PORTAL_TRANSFER_LOCKS.clear();
        EXPLICIT_TRANSFERS.clear();
        PREFERENCES.clear();
    }
}

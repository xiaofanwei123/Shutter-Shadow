package com.xfw.shuttershadow;

import com.xfw.shuttershadow.network.DimensionFilmStartS2C;
import com.xfw.shuttershadow.api.SeamlessTeleportation;
import com.xfw.shuttershadow.network.RemoteCameraSession;
import com.xfw.shuttershadow.api.DimensionFilters;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.camera.CameraOnStand;
import io.github.mortuusars.exposure.world.item.camera.Attachment;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One-way physical selfie hand-off.
 *
 * <p>The player is moved only when Exposure is about to take a selfie with a
 * dimension filter and the special film roll. Immersive Portals performs the
 * dimension switch without the vanilla loading overlay. The original Exposure
 * call is delayed until the client confirms that its local world has changed;
 * after that call the player remains in the target dimension.</p>
 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class DimensionFilmCapture {
    private static final int READY_TIMEOUT_TICKS = 100;
    /** Prevents a stale/global IP portal from immediately reversing this move. */
    private static final int PORTAL_TRANSFER_LOCK_TICKS = 200;
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Integer> PORTAL_TRANSFER_LOCKS = new HashMap<>();
    /** 手动和红石支架共同使用的个人传送偏好。 */
    private static final Map<UUID, Boolean> STAND_TELEPORT_PREFERENCES = new HashMap<>();
    /** True only while this class is inside IP's explicit transfer call. */
    private static final Set<UUID> EXPLICIT_TRANSFERS = new HashSet<>();
    private static long nextTransaction;

    private DimensionFilmCapture() {
    }

    /** Implemented by the CameraItem mixin to invoke Exposure's protected method. */
    public interface TakePhotoInvoker {
        void shuttershadow$invokeTakePhoto(CameraHolder holder, ServerPlayer player, ItemStack camera);
    }

    private enum State {
        WAITING_FOR_CLIENT,
        CALLING_EXPOSURE
    }

    /** Source information captured before the player is moved to the target level. */
    static record SourceSnapshot(ResourceLocation dimension, Vec3 position,
                                 ResourceLocation biome) {
    }

    private static final class Pending {
        private final long transaction;
        private final ServerPlayer player;
        private final CameraItem item;
        private final CameraHolder holder;
        private final ItemStack camera;
        private final ResourceKey<Level> targetDimension;
        private final SourceSnapshot source;
        private State state = State.WAITING_FOR_CLIENT;
        private int age;

        private Pending(long transaction, ServerPlayer player, CameraItem item,
                        CameraHolder holder, ItemStack camera,
                        ResourceKey<Level> targetDimension, SourceSnapshot source) {
            this.transaction = transaction;
            this.player = player;
            this.item = item;
            this.holder = holder;
            this.camera = camera;
            this.targetDimension = targetDimension;
            this.source = source;
        }
    }

    /** Returns source metadata only during the synchronous delayed photo call. */
    static SourceSnapshot activeSource(CameraHolder holder) {
        ServerPlayer player = holder.getServerPlayerExecutingExposure().orElse(null);
        if (player == null) return null;

        Pending pending = PENDING.get(player.getUUID());
        return pending != null && pending.state == State.CALLING_EXPOSURE
                ? pending.source : null;
    }

    /**
     * Intercepts only the first Exposure takePhoto call. Returning true tells
     * the mixin to cancel that call until the IP client world is ready.
     */
    public static boolean beginIfNeeded(CameraItem item, CameraHolder holder,
                                        ServerPlayer player, ItemStack camera) {
        // A stand is handled after Exposure's takePhoto returns. Keeping the player in
        // the source level during the shot preserves the stand's camera holder
        // and the active Immersive Portals remote capture session.
        if (holder.asHolderEntity() instanceof CameraStandEntity) return false;
        Pending current = PENDING.get(player.getUUID());
        if (current != null) {
            // The delayed invocation is allowed through exactly once.
            return current.state != State.CALLING_EXPOSURE;
        }

        if (!hasDimensionFilmSelfie(item, camera)) return false;

        ItemStack filter = Attachment.FILTER.get(camera).getForReading();
        DimensionFilters.Route mapping = DimensionFilters.resolve(
                player.serverLevel().registryAccess(), filter,
                player.serverLevel().dimension().location());
        if (mapping == null || mapping.dimension() == null) return false;

        ResourceKey<Level> targetKey = ResourceKey.create(Registries.DIMENSION, mapping.dimension());
        if (targetKey.equals(player.level().dimension())) {
            // Already in the requested level. Exposure can capture normally.
            return false;
        }
        ServerLevel target = player.getServer().getLevel(targetKey);
        if (target == null) return false;

        double scale = DimensionFilters.horizontalScale(mapping, player.serverLevel(), target);
        Vec3 targetPosition = DimensionFilters.mapAbsolute(player.position(), scale);
        long transaction = ++nextTransaction;
        SourceSnapshot source = snapshot(player);
        Pending created = new Pending(transaction, player, item, holder, camera,
                targetKey, source);
        PENDING.put(player.getUUID(), created);

        try {
            // IP keeps the player object and camera UI alive while replacing the
            // client world, avoiding vanilla's respawn/loading overlay.
            teleport(player, targetKey, targetPosition);
            PacketDistributor.sendToPlayer(player,
                    new DimensionFilmStartS2C(transaction, targetKey.location()));
            return true;
        } catch (RuntimeException exception) {
            PENDING.remove(player.getUUID(), created);
            PORTAL_TRANSFER_LOCKS.remove(player.getUUID());
            return false;
        }
    }

    /** 手动和红石支架在拍摄后传送同一份 Exposure 出镜名单中的玩家。 */
    public static void teleportStandPlayersAfterPhoto(CameraHolder holder, ItemStack camera) {
        if (!(holder.asHolderEntity() instanceof CameraStandEntity) || !hasPlayerDimensionFilm(camera)) return;
        RemoteCaptureContext remote = RemoteCaptureContext.resolve(holder, camera);
        if (remote == null) return;
        teleportStandPlayersAfterPhoto(remote, camera, remote.playersInFrame(camera));
    }

    /** 支架使用曝光时固定的出镜名单，后来走入镜头的人不参与这次传送。 */
    public static void teleportStandPlayersAfterPhoto(RemoteCaptureContext remote, ItemStack camera,
                                                       List<ServerPlayer> players) {
        if (!hasPlayerDimensionFilm(camera)) return;
        Level sourceLevel = remote.source().asHolderEntity().level();
        for (ServerPlayer player : players) {
            if (player.isAlive() && !player.isRemoved() && player.level() == sourceLevel
                    && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                    && acceptsStandTeleport(player)) {
                // 跨维后源支架已无法找到操作者，必须在离开前完整注销其控制状态。
                CameraOperator operator = (CameraOperator) player;
                if (operator.getActiveExposureCamera() instanceof CameraOnStand onStand
                        && onStand.getOperator() == operator
                        && onStand.getStand() == remote.source().asHolderEntity()) {
                    onStand.getStand().stopControlling();
                    operator.removeActiveExposureCamera();
                }
                // 必须先停止源维度取景会话，避免它在真实传送后重新接管画面。
                RemoteCameraSession.closeBeforeDimensionTeleport(player);
                teleport(player, remote.level().dimension(), remote.targetPosition(player));
                // 让客户端先处理 IP 的世界切换，再结束 Exposure 的远程相机状态。
                RemoteCameraSession.finishDimensionTeleport(player);
            }
        }
    }

    /** 手持自拍与支架共用公开无缝传送入口和防回传保护，标记在 finally 中释放。 */
    private static void teleport(ServerPlayer player, ResourceKey<Level> targetKey, Vec3 targetPosition) {
        UUID uuid = player.getUUID();
        PORTAL_TRANSFER_LOCKS.put(uuid, PORTAL_TRANSFER_LOCK_TICKS);
        EXPLICIT_TRANSFERS.add(uuid);
        try {
            SeamlessTeleportation.teleportPlayer(player,
                    player.getServer().getLevel(targetKey), targetPosition);
        } finally {
            EXPLICIT_TRANSFERS.remove(uuid);
        }
    }

    /** Called by the IP mixin before a portal-driven player move. */
    public static boolean shouldBlockPortalTeleport(ServerPlayer player) {
        return PORTAL_TRANSFER_LOCKS.containsKey(player.getUUID());
    }

    /** Returns whether the current IP call was initiated by this mod. */
    public static boolean isExplicitTransferInProgress(ServerPlayer player) {
        return EXPLICIT_TRANSFERS.contains(player.getUUID());
    }

    /** 更新该玩家对所有支架维度胶卷传送的接受偏好。 */
    public static void setStandTeleportPreference(ServerPlayer player, boolean accepted) {
        STAND_TELEPORT_PREFERENCES.put(player.getUUID(), accepted);
    }

    /** 截图与真实传送共用同一份玩家偏好；拒绝传送不会禁用远景拍摄。 */
    public static boolean acceptsStandTeleport(ServerPlayer player) {
        return STAND_TELEPORT_PREFERENCES.getOrDefault(player.getUUID(), true);
    }

    /** Called by the client after IP has installed the target ClientLevel. */
    public static void clientReady(ServerPlayer player, long transaction) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null || pending.transaction != transaction
                || pending.state != State.WAITING_FOR_CLIENT
                || !player.level().dimension().equals(pending.targetDimension)
                || !hasDimensionFilmSelfie(pending.item, pending.camera)) {
            return;
        }

        if (!(pending.item instanceof TakePhotoInvoker invoker)) {
            PENDING.remove(player.getUUID(), pending);
            return;
        }

        pending.state = State.CALLING_EXPOSURE;
        try {
            // The mixin sees CALLING_EXPOSURE and lets the original method run.
            invoker.shuttershadow$invokeTakePhoto(pending.holder, player, pending.camera);
        } finally {
            // This is intentionally one-way: the player stays in the target
            // dimension after the photograph is created.
            PENDING.remove(player.getUUID(), pending);
        }
    }

    private static SourceSnapshot snapshot(ServerPlayer player) {
        ResourceLocation biome = player.serverLevel().getBiome(player.blockPosition())
                .unwrapKey()
                .map(key -> key.location())
                .orElse(null);
        return new SourceSnapshot(player.level().dimension().location(),
                player.position(), biome);
    }

    /**
     * Checks the camera state without resolving a source route. After the
     * teleport the player is already in the target dimension, so the original
     * source-to-target route is intentionally no longer present.
     */
    private static boolean hasDimensionFilmSelfie(CameraItem item, ItemStack camera) {
        if (!item.isInSelfieMode(camera)) return false;
        return hasPlayerDimensionFilm(camera);
    }

    /** Player dimension film is the only film allowed to trigger player selfie teleportation. */
    public static boolean hasPlayerDimensionFilm(ItemStack camera) {
        ItemStack film = Attachment.FILM.get(camera).getForReading();
        return film.getItem() instanceof PlayerDimensionFilmRollItem
                && !Attachment.FILTER.get(camera).getForReading().isEmpty();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Map.Entry<UUID, Pending>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next().getValue();
            if (!pending.player.isAlive() || pending.player.isRemoved()
                    || pending.state != State.WAITING_FOR_CLIENT
                    || ++pending.age > READY_TIMEOUT_TICKS) {
                iterator.remove();
            }
        }
        PORTAL_TRANSFER_LOCKS.replaceAll((uuid, ticks) -> ticks - 1);
        PORTAL_TRANSFER_LOCKS.values().removeIf(ticks -> ticks <= 0);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
            PORTAL_TRANSFER_LOCKS.remove(player.getUUID());
            EXPLICIT_TRANSFERS.remove(player.getUUID());
            STAND_TELEPORT_PREFERENCES.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
        PORTAL_TRANSFER_LOCKS.clear();
        EXPLICIT_TRANSFERS.clear();
        STAND_TELEPORT_PREFERENCES.clear();
    }
}

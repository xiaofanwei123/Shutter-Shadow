package com.xfw.shuttershadow.camera;

import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.item.PlayerDimensionFilmRollItem;

import com.xfw.shuttershadow.network.DimensionFilmStartS2C;
import com.xfw.shuttershadow.api.CameraDimensionTeleportEvent;
import com.xfw.shuttershadow.api.CameraCaptureContext;
import com.xfw.shuttershadow.network.RemoteCameraSession;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.camera.CameraOnStand;
import io.github.mortuusars.exposure.world.item.camera.Attachment;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 管理玩家维度胶卷的自拍和支架传送事务。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class DimensionFilmCapture {
    private static final int READY_TIMEOUT_TICKS = 100;
    /** 短暂保护本次传送，避免旧状态立即触发反向移动。 */
    private static final int PORTAL_TRANSFER_LOCK_TICKS = 200;
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Integer> PORTAL_TRANSFER_LOCKS = new HashMap<>();
    /** 手动和红石支架共同使用的个人传送偏好。 */
    private static final Map<UUID, Boolean> STAND_TELEPORT_PREFERENCES = new HashMap<>();
    /** 仅在本类执行明确的无缝传送调用期间为真。 */
    private static final Set<UUID> EXPLICIT_TRANSFERS = new HashSet<>();
    private static long nextTransaction;

    /** 禁止实例化此工具类。 */
    private DimensionFilmCapture() {
    }

    /** 供相机注入钩子调用原生拍摄方法的接口。 */
    public interface TakePhotoInvoker {
        /** 以原CameraHolder、服务端玩家与相机物品调用Exposure原拍摄方法。 */
        void shuttershadow$invokeTakePhoto(CameraHolder holder, ServerPlayer player, ItemStack camera);
    }

    /** 标记自拍传送等待世界切换和执行拍摄的阶段。 */
    private enum State {
        WAITING_FOR_CLIENT,
        CALLING_EXPOSURE
    }

    /** 单个玩家胶卷自拍事务，持有原相机引用、目标维度、来源快照、状态及年龄。 */
    private static final class Pending {
        private final long transaction;
        private final ServerPlayer player;
        private final CameraItem item;
        private final CameraHolder holder;
        private final ItemStack camera;
        private final CameraCaptureContext context;
        private final ItemStack film;
        private final ItemStack filter;
        private ResourceKey<Level> targetDimension;
        private State state = State.WAITING_FOR_CLIENT;
        private int age;

        /** 保存事务参数。 */
        private Pending(long transaction, ServerPlayer player, CameraItem item,
                        CameraHolder holder, ItemStack camera,
                        ResourceKey<Level> targetDimension) {
            this.transaction = transaction;
            this.player = player;
            this.item = item;
            this.holder = holder;
            this.camera = camera;
            context = CameraCaptureEvents.context(camera);
            film = Attachment.FILM.get(camera).getForReading().copy();
            filter = Attachment.FILTER.get(camera).getForReading().copy();
            this.targetDimension = targetDimension;
        }
    }

    /** 拦截手持玩家胶卷自拍，解析当前滤镜路由，保存来源快照，开启事务、传送并发送DimensionFilmStart。 */
    public static boolean beginIfNeeded(CameraItem item, CameraHolder holder,
                                        ServerPlayer player, ItemStack camera) {
        // 支架传送在原拍摄返回后处理，拍照期间保持玩家在来源世界，
        // 以保留原支架拍摄者
        // 和正在使用的远维度拍摄会话。
        if (holder.asHolderEntity() instanceof CameraStandEntity) return false;
        Pending current = PENDING.get(player.getUUID());
        if (current != null) {
            // 延迟调用只放行一次。
            return current.state != State.CALLING_EXPOSURE;
        }

        if (!item.isInSelfieMode(camera) || !CameraCaptureEvents.transfersPlayers(camera)) return false;
        var plan = CameraCaptureEvents.plan(camera);
        ResourceKey<Level> targetKey = ResourceKey.create(Registries.DIMENSION, plan.getPlayerDimension());
        ServerLevel target = player.getServer().getLevel(targetKey);
        if (target == null) return false;

        Vec3 targetPosition = CameraCaptureEvents.playerDestination(camera, player, target);
        long transaction = ++nextTransaction;
        Pending created = new Pending(transaction, player, item, holder, camera,
                targetKey);
        PENDING.put(player.getUUID(), created);

        try {
            // 无缝传送保留玩家实例和相机界面，
            // 只替换客户端世界，避免原版重生和加载界面。
            List<ServerPlayer> selected = CameraCaptureEvents.selectPlayers(camera, List.of(player));
            if (selected.isEmpty() || !teleport(player, targetKey, targetPosition, camera)) {
                PENDING.remove(player.getUUID(), created);
                return false;
            }
            created.targetDimension = player.level().dimension();
            PacketDistributor.sendToPlayer(player,
                    new DimensionFilmStartS2C(transaction, created.targetDimension.location()));
            return true;
        } catch (RuntimeException exception) {
            PENDING.remove(player.getUUID(), created);
            PORTAL_TRANSFER_LOCKS.remove(player.getUUID());
            throw exception;
        }
    }

    /** 支架普通入口：确认玩家胶卷，解析远场并计算出镜玩家后委托列表重载。 */
    public static void teleportStandPlayersAfterPhoto(CameraHolder holder, ItemStack camera) {
        if (!(holder.asHolderEntity() instanceof CameraStandEntity) || !CameraCaptureEvents.transfersPlayers(camera)) return;
        RemoteCaptureContext remote = CameraCaptureEvents.playerContext(camera);
        if (remote == null) return;
        teleportStandPlayersAfterPhoto(remote, camera, CameraCaptureEvents.selectPlayers(camera, remote.playersInFrame(camera)));
    }

    /** 对冻结的出镜玩家逐个确认存活、来源世界、在线身份及个人同意状态。 */
    public static void teleportStandPlayersAfterPhoto(RemoteCaptureContext remote, ItemStack camera,
                                                       List<ServerPlayer> players) {
        if (!CameraCaptureEvents.transfersPlayers(camera)) return;
        Level sourceLevel = remote.source().asHolderEntity().level();
        for (ServerPlayer player : players) {
            if (player.isAlive() && !player.isRemoved() && player.level() == sourceLevel
                    && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                    && acceptsStandTeleport(player)) {
                // 跨维后源支架已无法找到操作者，必须在离开前完整注销其控制状态。
                boolean moved = teleport(player, remote.level().dimension(),
                        CameraCaptureEvents.playerDestination(camera, player, remote.level()), camera, () -> {
                            CameraOperator operator = (CameraOperator) player;
                            if (operator.getActiveExposureCamera() instanceof CameraOnStand onStand
                                    && onStand.getOperator() == operator
                                    && onStand.getStand() == remote.source().asHolderEntity()) {
                                onStand.getStand().stopControlling();
                                operator.removeActiveExposureCamera();
                            }
                            RemoteCameraSession.close(player);
                        });
                if (moved) RemoteCameraSession.finishDimensionTeleport(player);
            }
        }
    }

    /** 执行明确的胶卷传送，成功换维后发布相机传送完成事件。 */
    private static boolean teleport(ServerPlayer player, ResourceKey<Level> targetKey, Vec3 targetPosition,
                                 ItemStack camera) {
        return teleport(player, targetKey, targetPosition, camera, () -> {});
    }

    /** 获准传送后才清理支架来源状态，取消请求不退出取景器。 */
    private static boolean teleport(ServerPlayer player, ResourceKey<Level> targetKey, Vec3 targetPosition,
                                     ItemStack camera, Runnable beforeMove) {
        ResourceKey<Level> sourceDimension = player.level().dimension();
        UUID uuid = player.getUUID();
        PORTAL_TRANSFER_LOCKS.put(uuid, PORTAL_TRANSFER_LOCK_TICKS);
        EXPLICIT_TRANSFERS.add(uuid);
        try {
            ServerPlayer moved = CameraTransfers.teleportPlayer(player,
                    player.getServer().getLevel(targetKey), targetPosition, camera, beforeMove);
            if (moved == null) { PORTAL_TRANSFER_LOCKS.remove(uuid); return false; }
            if (!sourceDimension.equals(player.level().dimension()))
                NeoForge.EVENT_BUS.post(new CameraDimensionTeleportEvent(player, camera, sourceDimension));
            return true;
        } finally {
            EXPLICIT_TRANSFERS.remove(uuid);
        }
    }

    /** 返回玩家是否仍处于胶卷传送保护期，供底层拒绝意外重复传送。 */
    public static boolean shouldBlockPortalTeleport(ServerPlayer player) {
        return PORTAL_TRANSFER_LOCKS.containsKey(player.getUUID());
    }

    /** 检查当前调用是否来自明确的胶卷传送作用域，允许该次预期移动穿过保护。 */
    public static boolean isExplicitTransferInProgress(ServerPlayer player) {
        return EXPLICIT_TRANSFERS.contains(player.getUUID());
    }

    /** 保存玩家对支架胶卷传送的同意状态。 */
    public static void setStandTeleportPreference(ServerPlayer player, boolean accepted) {
        STAND_TELEPORT_PREFERENCES.put(player.getUUID(), accepted);
    }

    /** 读取同意状态，未设置默认允许。 */
    public static boolean acceptsStandTeleport(ServerPlayer player) {
        return STAND_TELEPORT_PREFERENCES.getOrDefault(player.getUUID(), true);
    }

    /** 验证客户端确认的事务号、状态、当前目标世界及胶卷自拍状态。 */
    public static void clientReady(ServerPlayer player, long transaction) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null || pending.transaction != transaction
                || pending.state != State.WAITING_FOR_CLIENT) {
            return;
        }
        var active = ((CameraOperator) player).getActiveExposureCamera();
        if (!player.level().dimension().equals(pending.targetDimension)
                || active == null || active.getItemStack() != pending.camera
                || !ItemStack.isSameItemSameComponents(pending.film, Attachment.FILM.get(pending.camera).getForReading())
                || !ItemStack.isSameItemSameComponents(pending.filter, Attachment.FILTER.get(pending.camera).getForReading())
                || !pending.item.isInSelfieMode(pending.camera)
                || !CameraCaptureEvents.transfersPlayers(pending.camera)
                || !pending.item.canTakePhoto(pending.holder, pending.camera)) {
            PENDING.remove(player.getUUID(), pending);
            CameraCaptureEvents.failed(pending.context, "自拍恢复时相机或附件已经变化");
            return;
        }

        if (!(pending.item instanceof TakePhotoInvoker invoker)) {
            PENDING.remove(player.getUUID(), pending);
            CameraCaptureEvents.failed(pending.context, "原生相机拍摄入口不可用");
            return;
        }

        pending.state = State.CALLING_EXPOSURE;
        try {
            // 执行拍摄状态会让注入钩子放行原方法。
            invoker.shuttershadow$invokeTakePhoto(pending.holder, player, pending.camera);
        } finally {
            // 拍摄结束后保持玩家位于目标维度，
            // 不自动传送回来源世界。
            PENDING.remove(player.getUUID(), pending);
        }
    }

    /** 确认附件胶卷为PlayerDimensionFilmRollItem且滤镜非空。 */
    public static boolean hasPlayerDimensionFilm(ItemStack camera) {
        ItemStack film = Attachment.FILM.get(camera).getForReading();
        return film.getItem() instanceof PlayerDimensionFilmRollItem
                && !Attachment.FILTER.get(camera).getForReading().isEmpty();
    }

    /** 移除死亡、离线、过期或不再等待客户端的事务。 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (Pending pending : List.copyOf(PENDING.values())) {
            if (!pending.player.isAlive() || pending.player.isRemoved()
                    || pending.state != State.WAITING_FOR_CLIENT
                    || ++pending.age > READY_TIMEOUT_TICKS) {
                if (PENDING.remove(pending.player.getUUID(), pending)) {
                    CameraCaptureEvents.failed(pending.context, "自拍换维确认超时或执行者失效");
                }
            }
        }
        PORTAL_TRANSFER_LOCKS.replaceAll((uuid, ticks) -> ticks - 1);
        PORTAL_TRANSFER_LOCKS.values().removeIf(ticks -> ticks <= 0);
    }

    /** 玩家登出时清除其事务、保护锁、显式作用域及支架同意状态。 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
            PORTAL_TRANSFER_LOCKS.remove(player.getUUID());
            EXPLICIT_TRANSFERS.remove(player.getUUID());
            STAND_TELEPORT_PREFERENCES.remove(player.getUUID());
        }
    }

    /** 服务端停止时清空全部事务与玩家偏好，避免下一次单人世界复用静态状态。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
        PORTAL_TRANSFER_LOCKS.clear();
        EXPLICIT_TRANSFERS.clear();
        STAND_TELEPORT_PREFERENCES.clear();
    }
}

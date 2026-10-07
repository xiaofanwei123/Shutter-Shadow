package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.camera.DimensionFilmCapture;
import com.xfw.shuttershadow.camera.ExposureVisibility;
import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.camera.CameraEnchantments;
import com.xfw.shuttershadow.camera.MobDimensionFilmCapture;
import com.xfw.shuttershadow.camera.RemoteCaptureContext;
import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.ShuttershadowConfig;
import com.xfw.shuttershadow.api.ChunkLoading;
import com.xfw.shuttershadow.api.ChunkLoader;
import com.xfw.shuttershadow.util.McHelper;
import io.github.mortuusars.exposure.ExposureServer;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.inventory.CameraOnStandAttachmentsMenu;
import io.github.mortuusars.exposure.world.item.camera.Attachment;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import io.github.mortuusars.exposure.world.item.camera.CameraSettings;
import io.github.mortuusars.exposure.world.item.camera.ShutterState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 管理服务端支架拍摄、照片完成确认和胶卷传送。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class RemoteStandPreparation {
    private static final long AUTHORIZATION_REFRESH_MILLIS = 20_000L;
    private static final int SOURCE_CAPTURE_TIMEOUT_TICKS = 2_400;

    /** ExposureRepositoryRemoteMixin实现的仓库预期上传窗口桥。 */
    public interface UploadWindow {
        /** 为同一玩家/照片续期既有预期上传授权。 */
        void shuttershadow$refreshExpected(ServerPlayer player, String exposureId);
        /** 取消同一玩家/照片的预期上传授权。 */
        void shuttershadow$cancelExpected(ServerPlayer player, String exposureId);
    }
    /** 红石计时拍摄跨 tick，需要将触发来源保留到真正的 takePhoto。 */
    private static final Map<UUID, Armed> ARMED = new HashMap<>();
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    /** 记录红石释放时支架和相机实例，计时器期间持续辨认红石拍摄。 */
    private record Armed(CameraStandEntity stand, ItemStack camera) {
    }

    /** 支架拍摄依次经过准备、曝光、等待图片和完成状态。 */
    private enum State {
        PREPARING,
        EXPOSING,
        WAITING_FOR_IMAGE,
        FINISHING
    }

    /** 保存支架/摄影师/相机实例、胶卷和滤镜快照、源视角、序号、Frame与延后声音。 */
    private static final class Pending {
        private final CameraItem item;
        private final CameraStandEntity stand;
        private final ServerPlayer player;
        private final ItemStack camera;
        private final ItemStack filter;
        private ItemStack film;
        private Frame frameToCommit;
        private boolean openSoundPending;
        private boolean closeSoundPending;
        private Runnable shutterClosedAction;
        private final RemoteCaptureContext remote;
        private final boolean sourceCapture;
        private final boolean discardImage;
        private final long sequence;
        private final Vec3 sourceOrigin;
        private final ServerLevel sourceLevel;
        private ChunkLoader transferLoader;
        private final float pitch;
        private final float yaw;
        private final RemoteSceneStartS2C scene;
        private String exposureId;
        private long nextAuthorizationRefresh;
        private State state = State.PREPARING;
        private int sourceWaitTicks;
        private int sourceTimeoutTicks = SOURCE_CAPTURE_TIMEOUT_TICKS;
        private List<ServerPlayer> playersInFrame = List.of();

        /** 保存拍摄参数、曝光失效标记、负序号和支架位置/转角，复制滤镜与胶卷快照。 */
        private Pending(CameraItem item, CameraStandEntity stand, ServerPlayer player,
                        ItemStack camera, RemoteCaptureContext remote, RemoteSceneStartS2C scene,
                        boolean sourceCapture) {
            this.item = item;
            this.stand = stand;
            this.player = player;
            this.camera = camera;
            this.remote = remote;
            this.scene = scene;
            this.sourceCapture = sourceCapture;
            discardImage = CameraEnchantments.has(camera, CameraEnchantments.EXPOSURE_FAILURE);
            sequence = scene == null ? RemoteCameraSession.allocateCaptureSequence() : scene.sequence();
            sourceOrigin = stand.position();
            sourceLevel = (ServerLevel) stand.level();
            pitch = stand.getXRot();
            yaw = stand.getYRot();
            filter = Attachment.FILTER.get(camera).getForReading().copy();
            film = Attachment.FILM.get(camera).getForReading().copy();
        }

        /** 逐项检查支架和摄影师存活/身份、世界、出镜玩家、支架位置、转角以及附件快照。 */
        private String invalidReason() {
            if (stand.isRemoved() || !stand.isAlive()) return "stand removed";
            if (stand.getCamera() != camera) return "camera replaced";
            if (!player.isAlive() || player.isRemoved()
                    || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
                return "executor unavailable";
            }
            if (remote != null && player.getServer().getLevel(remote.level().dimension()) != remote.level()) {
                return "target dimension removed";
            }
            if (stand.level() != sourceLevel || player.level() != sourceLevel) return "executor left source dimension";
            double playerRadius = ShuttershadowConfig.standPlayerRadius();
            for (ServerPlayer projected : playersInFrame) {
                if (!projected.isAlive() || projected.isRemoved() || projected.level() != stand.level()
                        || player.getServer().getPlayerList().getPlayer(projected.getUUID()) != projected
                        || stand.distanceToSqr(projected) > playerRadius * playerRadius) {
                    return "projected player left capture area";
                }
            }
            if (!stand.position().equals(sourceOrigin)) return "stand moved";
            if (!discardImage && (stand.getXRot() != pitch || stand.getYRot() != yaw)) return "stand rotated";
            if (!ItemStack.isSameItemSameComponents(filter,
                    Attachment.FILTER.get(camera).getForReading())) return "filter changed";
            return ItemStack.isSameItemSameComponents(film,
                    Attachment.FILM.get(camera).getForReading()) ? null : "film changed";
        }

        /** 存在Exposure ID时续期仓库上传授权并更新下一次续期时间。 */
        private void refreshUploadAuthorization() {
            if (exposureId == null) return;
            ((UploadWindow) ExposureServer.exposureRepository())
                    .shuttershadow$refreshExpected(player, exposureId);
            nextAuthorizationRefresh = System.currentTimeMillis() + AUTHORIZATION_REFRESH_MILLIS;
        }

        /** 按Holder实体、ServerPlayer和相机引用严格匹配事务。 */
        private boolean matches(CameraHolder holder, ServerPlayer player, ItemStack camera) {
            return holder.asHolderEntity() == stand && this.player == player && this.camera == camera;
        }

        /** 非生物胶卷直接就绪，生物胶卷按当前范围更新唯一搜索窗口。 */
        private boolean mobChunksReady() {
            if (remote == null || !MobDimensionFilmCapture.hasMobDimensionFilm(camera)) return true;
            int radius = Math.min((ShuttershadowConfig.mobCaptureRadius() + 15) / 16,
                    McHelper.getPlayerLoadDistance(player));
            BlockPos center = remote.asHolderEntity().blockPosition();
            if (transferLoader == null || !transferLoader.dimension().equals(remote.level().dimension())
                    || transferLoader.x() != (center.getX() >> 4)
                    || transferLoader.z() != (center.getZ() >> 4) || transferLoader.radius() != radius) {
                ChunkLoader replacement = new ChunkLoader(remote.level().dimension(),
                        center.getX() >> 4, center.getZ() >> 4, radius);
                ChunkLoading.addGlobalChunkLoader(player.getServer(), replacement);
                if (transferLoader != null) {
                    ChunkLoading.removeGlobalChunkLoader(player.getServer(), transferLoader);
                }
                transferLoader = replacement;
            }
            return transferLoader.isFullyLoaded(player.getServer());
        }

        /** 红石在源世界按Exposure选玩家。 */
        private List<ServerPlayer> candidatesForPhoto() {
            // 红石拍原维度；手动取景的传送名单使用目标空间投影，不能先按源视锥筛掉。
            List<ServerPlayer> players = sourceCapture
                    ? ExposureVisibility.playersInFrame(stand, camera).stream()
                            .filter(player -> player instanceof ServerPlayer)
                            .map(player -> (ServerPlayer) player).toList()
                    : remote.playersInFrame(camera);
            return sourceCapture || discardImage ? players
                    : RemoteCameraSession.syncedCapturePlayers(sequence, players);
        }

        /** 移除本事务的全局生物搜索窗口并清引用。 */
        private void releaseTransferLoader() {
            if (transferLoader == null) return;
            ChunkLoading.removeGlobalChunkLoader(player.getServer(), transferLoader);
            transferLoader = null;
        }
    }

    /** 禁止实例化此工具类。 */
    private RemoteStandPreparation() {
    }

    /** 客户端直接执行原释放。 */
    public static void redstoneRelease(CameraStandEntity stand, Runnable release) {
        if (stand.level().isClientSide) {
            release.run();
            return;
        }
        // 正在准备或出片的同一支架不重新 release，避免重启计时器和更换操作员。
        if (PENDING.containsKey(stand.getUUID())) return;
        ItemStack camera = stand.getCamera();
        if (!hasDimensionRoute(stand, camera)) {
            ARMED.remove(stand.getUUID());
            release.run();
            return;
        }
        Armed armed = new Armed(stand, camera);
        ARMED.put(stand.getUUID(), armed);
        try {
            release.run();
        } finally {
            // 立即拍摄已进入 PENDING；计时拍摄将保留到计时器再次 release。
            boolean timerRunning = camera.getItem() instanceof CameraItem item
                    && item.getTimer().isTicking(stand, camera);
            if (!PENDING.containsKey(stand.getUUID()) && !timerRunning) {
                ARMED.remove(stand.getUUID(), armed);
            }
        }
    }

    /** ARMED或已有sourceCapture事务即认定红石拍摄。 */
    public static boolean isRedstoneCapture(CameraStandEntity stand) {
        Pending pending = PENDING.get(stand.getUUID());
        return ARMED.containsKey(stand.getUUID())
                || pending != null && pending.sourceCapture;
    }

    /** 从服务端实际滤镜解析不同维度的有效路由。 */
    private static boolean hasDimensionRoute(CameraStandEntity stand, ItemStack camera) {
        if (!(camera.getItem() instanceof CameraItem)) return false;
        ServerLevel source = (ServerLevel) stand.level();
        DimensionFilters.Route route = DimensionFilters.resolve(
                Attachment.FILTER.get(camera).getForReading(), source.dimension().location());
        return route != null;
    }

    /** 按负序号判断照片loader是否仍由支架Pending拥有。 */
    public static boolean ownsCapture(long sequence) {
        return PENDING.values().stream().anyMatch(pending -> pending.sequence == sequence);
    }

    /** 按Exposure ID判断上传窗口是否仍被Pending拥有。 */
    public static boolean ownsExposure(String exposureId) {
        return PENDING.values().stream().anyMatch(pending -> exposureId.equals(pending.exposureId));
    }

    /** 仅接管支架。 */
    public static boolean beginIfNeeded(CameraItem item, CameraHolder holder,
                                        ServerPlayer player, ItemStack camera) {
        if (!(holder.asHolderEntity() instanceof CameraStandEntity stand)) return false;
        Pending current = PENDING.get(stand.getUUID());
        if (current != null) {
            return current.state != State.EXPOSING || !current.matches(holder, player, camera);
        }
        boolean sourceCapture = isRedstoneCapture(stand);
        // 红石计时期间可能取下或换成普通滤镜，必须在曝光前恢复原生拍摄范围。
        if (sourceCapture && !hasDimensionRoute(stand, camera)) {
            ARMED.remove(stand.getUUID());
            return false;
        }
        RemoteCaptureContext remote;
        RemoteSceneStartS2C scene = null;
        if (sourceCapture) {
            remote = DimensionFilmCapture.hasPlayerDimensionFilm(camera)
                    || MobDimensionFilmCapture.hasMobDimensionFilm(camera)
                    ? RemoteCaptureContext.resolveForTransfer(holder, camera) : null;
        } else {
            remote = RemoteCaptureContext.resolve(holder, camera);
            if (remote == null) return false;
            if (!CameraEnchantments.has(camera, CameraEnchantments.EXPOSURE_FAILURE)) {
                scene = RemoteCameraSession.openCapture(player, remote);
            }
        }
        PENDING.put(stand.getUUID(), new Pending(item, stand, player, camera, remote, scene, sourceCapture));
        ARMED.remove(stand.getUUID());
        return true;
    }

    /** 只有手动支架且相机引用一致、处于EXPOSING/FINISHING才暴露准备好的远场Holder。 */
    public static RemoteCaptureContext preparedContext(CameraHolder holder, ItemStack camera) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        return pending != null && !pending.sourceCapture && pending.camera == camera
                && (pending.state == State.EXPOSING || pending.state == State.FINISHING)
                ? pending.remote : null;
    }

    /** 对相同Holder/玩家/相机且处于EXPOSING的事务提供照片场景。 */
    public static RemoteSceneStartS2C captureScene(CameraHolder holder, ServerPlayer player,
                                                  ItemStack camera) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        return pending != null && pending.state == State.EXPOSING
                && pending.matches(holder, player, camera) ? pending.scene : null;
    }

    /** 对正在EXPOSING的红石源照片提供负事务序号。 */
    public static Long sourceCaptureSequence(CameraHolder holder, ServerPlayer player, ItemStack camera) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        return pending != null && pending.sourceCapture && pending.state == State.EXPOSING
                && pending.matches(holder, player, camera) ? pending.sequence : null;
    }

    /** 红石EXPOSING期间提供目标传送上下文，照片Holder仍是源支架。 */
    public static RemoteCaptureContext sourceTransferContext(CameraHolder holder, ItemStack camera) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        return pending != null && pending.sourceCapture && pending.camera == camera
                && pending.state == State.EXPOSING ? pending.remote : null;
    }

    /** 记录本次Exposure ID和下一次上传授权续期时刻。 */
    public static void recordExposure(CameraHolder holder, ServerPlayer player, ItemStack camera,
                                      String exposureId) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        if (pending == null || pending.state != State.EXPOSING
                || !pending.matches(holder, player, camera)) return;
        pending.exposureId = exposureId;
        pending.nextAuthorizationRefresh = System.currentTimeMillis() + AUTHORIZATION_REFRESH_MILLIS;
    }

    /** 在EXPOSING阶段把胶卷Frame缓存到事务，延迟照片数加1至客户端成功回执。 */
    public static boolean deferFrameCommit(ItemStack camera, Frame frame) {
        Pending pending = PENDING.values().stream()
                .filter(value -> value.camera == camera && value.state == State.EXPOSING)
                .findFirst().orElse(null);
        if (pending == null) return false;
        // 元数据与实体名单照常生成，只把实际写卷留到客户端完成截图之后。
        pending.frameToCommit = frame;
        return true;
    }

    /** 在EXPOSING/WAITING_FOR_IMAGE阶段记录需延后的开闭快门音，不立即播放。 */
    public static boolean deferShutterSound(CameraHolder holder, boolean closing) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        if (pending == null || (pending.state != State.EXPOSING && pending.state != State.WAITING_FOR_IMAGE)) {
            return false;
        }
        if (closing) pending.closeSoundPending = true;
        else pending.openSoundPending = true;
        return true;
    }

    /** 缓存需等成片才执行的快门关闭动作。 */
    public static boolean deferShutterClosed(CameraHolder holder, Runnable action) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        if (pending == null || (pending.state != State.EXPOSING && pending.state != State.WAITING_FOR_IMAGE)) {
            return false;
        }
        pending.shutterClosedAction = action;
        return true;
    }

    /** 把准备好的Frame加入胶卷，强制同步支架实体和正在打开的附件菜单，最后播放完成快门音。 */
    private static void commitFrameAndPlaySound(Pending pending) {
        if (pending.frameToCommit == null) {
            throw new IllegalStateException("Remote capture completed without a prepared film frame");
        }
        pending.item.addFrameToFilm(pending.camera, pending.frameToCommit);
        pending.frameToCommit = null;
        pending.stand.forceUpdate();
        // forceUpdate 只标脏；先发送照片数量，再发声音，不能依赖下一次实体跟踪 tick。
        var data = pending.stand.getEntityData().getNonDefaultValues();
        if (data != null) {
            ((ServerLevel) pending.stand.level()).getChunkSource().broadcast(pending.stand,
                    new ClientboundSetEntityDataPacket(pending.stand.getId(), data));
        }
        // 附件菜单持有胶卷副本；只更新 backing 槽，避免槽监听将旧附件写回相机。
        for (ServerPlayer viewer : pending.player.getServer().getPlayerList().getPlayers()) {
            if (viewer.containerMenu instanceof CameraOnStandAttachmentsMenu menu
                    && menu.getCamera().getStack() == pending.camera) {
                var attachments = pending.item.getAttachments();
                for (int i = 0; i < attachments.size(); i++) {
                    var slot = menu.getSlot(i);
                    ((SimpleContainer) slot.container).getItems().set(slot.getSlotIndex(),
                            attachments.get(i).get(pending.camera).getCopy());
                }
                // 完整同步不会触发客户端的假“插入胶卷”音，且数据包先于快门声。
                menu.broadcastFullState();
            }
        }
        playCompletionSound(pending);
    }

    /** 依次播放已延后的开/闭声，并运行已缓存的快门关闭动作。 */
    private static void playCompletionSound(Pending pending) {
        if (pending.openSoundPending) pending.item.getShutter().playOpenSound(pending.stand);
        if (pending.closeSoundPending) pending.item.getShutter().playCloseSound(pending.stand);
        if (pending.shutterClosedAction != null) pending.shutterClosedAction.run();
    }

    /** EXPOSING阶段要求相机Mixin推迟玩家传送至完成回执。 */
    public static boolean shouldDeferTeleport(CameraHolder holder) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        return pending != null && pending.state == State.EXPOSING;
    }

    /** 未建立Pending的普通拍摄结束时移除红石ARMED标记。 */
    public static void finishOrdinaryCapture(CameraHolder holder) {
        if (!PENDING.containsKey(holder.asHolderEntity().getUUID())) {
            ARMED.remove(holder.asHolderEntity().getUUID());
        }
    }

    /** 只处理摄影师/序号匹配的WAITING_FOR_IMAGE事务。 */
    public static void onCaptureFinished(ServerPlayer player, long sequence, boolean captured) {
        Pending pending = PENDING.values().stream()
                .filter(value -> value.player == player && value.sequence == sequence)
                .findFirst().orElse(null);
        if (pending == null || pending.state != State.WAITING_FOR_IMAGE) return;
        try {
            // 截图后 Exposure 还会处理与上传图片，最后留出一次完整的原生上传窗口。
            if (captured) pending.refreshUploadAuthorization();
            if (captured && pending.invalidReason() == null) {
                pending.state = State.FINISHING;
                commitFrameAndPlaySound(pending);
                if (pending.remote != null) {
                    DimensionFilmCapture.teleportStandPlayersAfterPhoto(
                            pending.remote, pending.camera, pending.playersInFrame);
                }
            }
        } finally {
            if (pending.state != State.FINISHING) discardPreparedShutter(pending);
            pending.releaseTransferLoader();
            PENDING.remove(pending.stand.getUUID(), pending);
        }
    }

    /** 取消照片生物事务和仓库预期上传，丢弃Frame/ID。 */
    private static void discardPreparedShutter(Pending pending) {
        String exposureId = pending.exposureId != null ? pending.exposureId
                : pending.frameToCommit != null ? pending.frameToCommit.identifier().id() : null;
        if (exposureId != null) {
            MobDimensionFilmCapture.cancelPending(pending.player, exposureId);
            ((UploadWindow) ExposureServer.exposureRepository())
                    .shuttershadow$cancelExpected(pending.player, exposureId);
        }
        pending.exposureId = null;
        pending.frameToCommit = null;
        if (pending.state == State.PREPARING || !pending.item.getShutter().isOpen(pending.camera)) return;
        pending.item.getShutter().setState(pending.camera, ShutterState.CLOSED);
        // 只同步仍持有本次相机的支架，不触碰玩家刚换上的另一台相机。
        if (!pending.stand.isRemoved() && pending.stand.getCamera() == pending.camera) {
            pending.stand.forceUpdate();
        }
    }

    /** 撤销快门和传送loader，手动照片额外关照片订阅。 */
    private static void cancelPrepared(Pending pending) {
        discardPreparedShutter(pending);
        pending.releaseTransferLoader();
        if (!pending.sourceCapture) RemoteCameraSession.close(pending.player, pending.sequence);
        if (pending.player.getServer().getPlayerList().getPlayer(pending.player.getUUID()) == pending.player) {
            PacketDistributor.sendToPlayer(pending.player,
                    new RemoteSceneStopS2C(pending.sequence));
        }
    }

    /** 清过期ARMED和无效Pending，续上传授权。 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ARMED.values().removeIf(armed -> armed.stand().isRemoved()
                || armed.stand().getCamera() != armed.camera()
                || !(armed.camera().getItem() instanceof CameraItem item)
                || !item.getTimer().isTicking(armed.stand(), armed.camera()));
        Iterator<Pending> iterator = PENDING.values().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            String invalidReason = pending.invalidReason();
            // 只限制原生源照片；按服务端实际 tick 计时，暂停或低 TPS 不消耗等待额度。
            if (invalidReason == null && pending.sourceCapture
                    && ++pending.sourceWaitTicks >= pending.sourceTimeoutTicks) {
                invalidReason = "source capture timed out";
            }
            if (invalidReason != null) {
                Shuttershadow.LOGGER.info("Remote photo {} cancelled during {}: {}",
                        pending.sequence, pending.state, invalidReason);
                iterator.remove();
                cancelPrepared(pending);
                continue;
            }
            if (pending.state == State.WAITING_FOR_IMAGE
                    && System.currentTimeMillis() >= pending.nextAuthorizationRefresh) {
                pending.refreshUploadAuthorization();
            }
            // 拍下当前取景画面，玩家传送不等待区块；仅生物胶卷等待扫描所需实体。
            if (pending.state != State.PREPARING || !pending.mobChunksReady()) continue;
            if (!(pending.item instanceof DimensionFilmCapture.TakePhotoInvoker invoker)) {
                iterator.remove();
                cancelPrepared(pending);
                continue;
            }
            try {
                // 红石只等服务端传送数据；手动远景继续同步原有场景与出镜玩家。
                if (pending.remote != null && (!pending.sourceCapture
                        || DimensionFilmCapture.hasPlayerDimensionFilm(pending.camera))) {
                    List<ServerPlayer> candidates = pending.candidatesForPhoto();
                    pending.playersInFrame = pending.remote.freezePlayersInFrame(candidates);
                    if (pending.sourceCapture) {
                        pending.playersInFrame = pending.playersInFrame.stream()
                                .filter(DimensionFilmCapture::acceptsStandTeleport).toList();
                    }
                }
                if (pending.scene != null) {
                    RemoteCameraSession.flushCapture(pending.player);
                }
                pending.state = State.EXPOSING;
                invoker.shuttershadow$invokeTakePhoto(pending.stand, pending.player, pending.camera);
                if (pending.discardImage) {
                    pending.state = State.FINISHING;
                    playCompletionSound(pending);
                    if (pending.remote != null) {
                        DimensionFilmCapture.teleportStandPlayersAfterPhoto(
                                pending.remote, pending.camera, pending.playersInFrame);
                    }
                    pending.releaseTransferLoader();
                    iterator.remove();
                    continue;
                }
                // 图片完成前不增加胶卷数量；继续核验同一胶卷，取消时不留下空白帧。
                pending.film = Attachment.FILM.get(pending.camera).getForReading().copy();
                if (pending.sourceCapture) {
                    pending.sourceWaitTicks = 0;
                    pending.sourceTimeoutTicks = SOURCE_CAPTURE_TIMEOUT_TICKS
                            + CameraSettings.SHUTTER_SPEED.getOrDefault(pending.camera).getDurationTicks();
                }
                pending.state = State.WAITING_FOR_IMAGE;
            } catch (RuntimeException exception) {
                iterator.remove();
                cancelPrepared(pending);
                Shuttershadow.LOGGER.error("Failed to start prepared remote stand photo {}.",
                        pending.stand.getUUID(), exception);
            }
        }
    }

    /** 登出时撤销该摄影师事务并释放传送及照片loader。 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PENDING.values().removeIf(pending -> {
            if (pending.player != player) return false;
            discardPreparedShutter(pending);
            pending.releaseTransferLoader();
            if (!pending.sourceCapture) RemoteCameraSession.close(player, pending.sequence);
            return true;
        });
    }

    /** 服务端停止清ARMED，撤销所有照片与loader，再清Pending。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ARMED.clear();
        PENDING.values().forEach(pending -> {
            discardPreparedShutter(pending);
            pending.releaseTransferLoader();
            if (!pending.sourceCapture) RemoteCameraSession.close(pending.player, pending.sequence);
        });
        PENDING.clear();
    }
}

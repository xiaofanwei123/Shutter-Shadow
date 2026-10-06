package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.DimensionFilmCapture;
import com.xfw.shuttershadow.ExposureVisibility;
import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.CameraEnchantments;
import com.xfw.shuttershadow.MobDimensionFilmCapture;
import com.xfw.shuttershadow.RemoteCaptureContext;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 手动支架保留远景；红石只拍源维度，图片完成后执行胶卷传送。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class RemoteStandPreparation {
    private static final long AUTHORIZATION_REFRESH_MILLIS = 20_000L;
    private static final int SOURCE_CAPTURE_TIMEOUT_TICKS = 2_400;

    /** 仅延长当前照片的 Exposure 上传授权，不改变其它照片的超时规则。 */
    public interface UploadWindow {
        void shuttershadow$refreshExpected(ServerPlayer player, String exposureId);
        void shuttershadow$cancelExpected(ServerPlayer player, String exposureId);
    }
    /** 红石计时拍摄跨 tick，需要将触发来源保留到真正的 takePhoto。 */
    private static final Map<UUID, Armed> ARMED = new HashMap<>();
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private record Armed(CameraStandEntity stand, ItemStack camera) {
    }

    private enum State {
        PREPARING,
        EXPOSING,
        WAITING_FOR_IMAGE,
        FINISHING
    }

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
        private final Set<ChunkLoader> transferLoaders = new HashSet<>();
        private final float pitch;
        private final float yaw;
        private final long preparationStartedAt = System.nanoTime();
        private long exposureStartedAt;
        private RemoteSceneStartS2C scene;
        private String exposureId;
        private long nextAuthorizationRefresh;
        private State state = State.PREPARING;
        private int sourceWaitTicks;
        private int sourceTimeoutTicks = SOURCE_CAPTURE_TIMEOUT_TICKS;
        private List<ServerPlayer> playersInFrame = List.of();

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

        private boolean valid() {
            return invalidReason() == null;
        }

        /** 区分主动失效与正常等待，避免取消日志只有下游的 TaskStoppedException。 */
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

        /** Exposure 的 60 秒上传授权只延长当前照片，保留原来的上传回调。 */
        private void refreshUploadAuthorization() {
            if (exposureId == null) return;
            ((UploadWindow) ExposureServer.exposureRepository())
                    .shuttershadow$refreshExpected(player, exposureId);
            nextAuthorizationRefresh = System.currentTimeMillis() + AUTHORIZATION_REFRESH_MILLIS;
        }

        private boolean matches(CameraHolder holder, ServerPlayer player, ItemStack camera) {
            return holder.asHolderEntity() == stand && this.player == player && this.camera == camera;
        }

        /** 生物扫描需要实际实体数据；玩家传送和照片均不等待目的地区块。 */
        private boolean mobChunksReady() {
            if (remote == null || !MobDimensionFilmCapture.hasMobDimensionFilm(camera)) return true;
            Set<ChunkLoader> required = new HashSet<>();
            int radius = Math.min((ShuttershadowConfig.mobCaptureRadius() + 15) / 16,
                    McHelper.getPlayerLoadDistance(player));
            BlockPos center = remote.asHolderEntity().blockPosition();
            required.add(new ChunkLoader(remote.level().dimension(),
                    center.getX() >> 4, center.getZ() >> 4, radius));
            for (ChunkLoader loader : required) {
                if (transferLoaders.add(loader)) {
                    ChunkLoading.addGlobalChunkLoader(player.getServer(), loader);
                }
            }
            transferLoaders.removeIf(loader -> {
                if (required.contains(loader)) return false;
                ChunkLoading.removeGlobalChunkLoader(player.getServer(), loader);
                return true;
            });
            return transferLoaders.stream().allMatch(loader -> loader.isFullyLoaded(player.getServer()));
        }

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

        private void releaseTransferLoaders() {
            transferLoaders.forEach(loader ->
                    ChunkLoading.removeGlobalChunkLoader(player.getServer(), loader));
            transferLoaders.clear();
        }
    }

    private RemoteStandPreparation() {
    }

    /** 只由 Exposure 红石组件中实际的 release 调用建立标记，手动快门不会走此入口。 */
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

    /** 准备、恢复拍摄与完成传送期间都沿用实际红石触发来源。 */
    public static boolean isRedstoneCapture(CameraStandEntity stand) {
        Pending pending = PENDING.get(stand.getUUID());
        return ARMED.containsKey(stand.getUUID())
                || pending != null && pending.sourceCapture;
    }

    /** 只根据滤镜路由限定维度相机，不查询或加载源照片不需要的目标世界。 */
    private static boolean hasDimensionRoute(CameraStandEntity stand, ItemStack camera) {
        if (!(camera.getItem() instanceof CameraItem)) return false;
        ServerLevel source = (ServerLevel) stand.level();
        DimensionFilters.Route route = DimensionFilters.resolve(source.registryAccess(),
                Attachment.FILTER.get(camera).getForReading(), source.dimension().location());
        return route != null && route.dimension() != null
                && !route.dimension().equals(source.dimension().location());
    }

    /** 手动远景准备或出片中的订阅不按普通孤儿订阅的时间上限清理。 */
    public static boolean ownsCapture(long sequence) {
        return PENDING.values().stream().anyMatch(pending -> pending.sequence == sequence);
    }

    /** 生物胶卷复用同一张支架照片的等待状态，避免额外设置准备超时。 */
    public static boolean ownsExposure(String exposureId) {
        return PENDING.values().stream().anyMatch(pending -> exposureId.equals(pending.exposureId));
    }

    /** 返回 true 时，Mixin 暂停原 takePhoto，后续服务端 tick 就绪后只恢复一次。 */
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

    /** 真正曝光的光照、实体扫描与元数据使用准备时同一个远程观察者。 */
    public static RemoteCaptureContext preparedContext(CameraHolder holder, ItemStack camera) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        return pending != null && !pending.sourceCapture && pending.camera == camera
                && (pending.state == State.EXPOSING || pending.state == State.FINISHING)
                ? pending.remote : null;
    }

    /** 复用已经准备完的订阅；禁止在恢复 takePhoto 时再创建第二个 loader。 */
    public static RemoteSceneStartS2C captureScene(CameraHolder holder, ServerPlayer player,
                                                  ItemStack camera) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        return pending != null && pending.state == State.EXPOSING
                && pending.matches(holder, player, camera) ? pending.scene : null;
    }

    /** 源维度照片只传回执序号，不携带任何目标维度渲染参数。 */
    public static Long sourceCaptureSequence(CameraHolder holder, ServerPlayer player, ItemStack camera) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        return pending != null && pending.sourceCapture && pending.state == State.EXPOSING
                && pending.matches(holder, player, camera) ? pending.sequence : null;
    }

    /** 生物传送独立读取目标世界；红石照片的世界、光照与元数据仍属于源维度。 */
    public static RemoteCaptureContext sourceTransferContext(CameraHolder holder, ItemStack camera) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        return pending != null && pending.sourceCapture && pending.camera == camera
                && pending.state == State.EXPOSING ? pending.remote : null;
    }

    /** 原 takePhoto 已建立上传授权后，记录该照片 ID，用于长时间绘制等待时续期。 */
    public static void recordExposure(CameraHolder holder, ServerPlayer player, ItemStack camera,
                                      String exposureId) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        if (pending == null || pending.state != State.EXPOSING
                || !pending.matches(holder, player, camera)) return;
        pending.exposureId = exposureId;
        pending.nextAuthorizationRefresh = System.currentTimeMillis() + AUTHORIZATION_REFRESH_MILLIS;
    }

    /** 保留原帧内容及事件时点，仅延后向胶卷追加照片。 */
    public static boolean deferFrameCommit(ItemStack camera, Frame frame) {
        Pending pending = PENDING.values().stream()
                .filter(value -> value.camera == camera && value.state == State.EXPOSING)
                .findFirst().orElse(null);
        if (pending == null) return false;
        // 元数据与实体名单照常生成，只把实际写卷留到客户端完成截图之后。
        pending.frameToCommit = frame;
        return true;
    }

    /** 支架的成功快门声随照片提交播放；实际快门组件仍由 Exposure 原样更新。 */
    public static boolean deferShutterSound(CameraHolder holder, boolean closing) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        if (pending == null || (pending.state != State.EXPOSING && pending.state != State.WAITING_FOR_IMAGE)) {
            return false;
        }
        if (closing) pending.closeSoundPending = true;
        else pending.openSoundPending = true;
        return true;
    }

    /** 过片声音与冷却在写卷后处理，满卷判断也使用已提交的正确数量。 */
    public static boolean deferShutterClosed(CameraHolder holder, Runnable action) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        if (pending == null || (pending.state != State.EXPOSING && pending.state != State.WAITING_FOR_IMAGE)) {
            return false;
        }
        pending.shutterClosedAction = action;
        return true;
    }

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
        Shuttershadow.LOGGER.info("Remote photo {} committed to film after {} ms preparing and {} ms capturing; completion shutter played.",
                pending.sequence, (pending.exposureStartedAt - pending.preparationStartedAt) / 1_000_000L,
                (System.nanoTime() - pending.exposureStartedAt) / 1_000_000L);
    }

    private static void playCompletionSound(Pending pending) {
        if (pending.openSoundPending) pending.item.getShutter().playOpenSound(pending.stand);
        if (pending.closeSoundPending) pending.item.getShutter().playCloseSound(pending.stand);
        if (pending.shutterClosedAction != null) pending.shutterClosedAction.run();
    }

    /** 手动和红石都等图片完成后再传送，保证执行客户端仍在源维度。 */
    public static boolean shouldDeferTeleport(CameraHolder holder) {
        Pending pending = PENDING.get(holder.asHolderEntity().getUUID());
        return pending != null && pending.state == State.EXPOSING;
    }

    /** 没有建立拍摄事务的快门，在原始世界查询结束后清除触发标记。 */
    public static void finishOrdinaryCapture(CameraHolder holder) {
        if (!PENDING.containsKey(holder.asHolderEntity().getUUID())) {
            ARMED.remove(holder.asHolderEntity().getUUID());
        }
    }

    /** 完成包先处理传送，再由网络层释放本次拍摄订阅；失败图片不触发传送。 */
    public static void onCaptureFinished(ServerPlayer player, long sequence, boolean captured) {
        Pending pending = PENDING.values().stream()
                .filter(value -> value.player == player && value.sequence == sequence)
                .findFirst().orElse(null);
        if (pending == null || pending.state != State.WAITING_FOR_IMAGE) return;
        try {
            // 截图后 Exposure 还会处理与上传图片，最后留出一次完整的原生上传窗口。
            if (captured) pending.refreshUploadAuthorization();
            if (captured && pending.valid()) {
                pending.state = State.FINISHING;
                commitFrameAndPlaySound(pending);
                if (pending.remote != null) {
                    DimensionFilmCapture.teleportStandPlayersAfterPhoto(
                            pending.remote, pending.camera, pending.playersInFrame);
                }
            }
        } finally {
            if (pending.state != State.FINISHING) discardPreparedShutter(pending);
            pending.releaseTransferLoaders();
            PENDING.remove(pending.stand.getUUID(), pending);
        }
    }

    /** 失败不走原生 close，否则长曝光会在取消后补响快门与过片声。 */
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

    /** 只取消本次支架截图，保留玩家当前 HUD 与其它取景会话。 */
    private static void cancelPrepared(Pending pending) {
        discardPreparedShutter(pending);
        pending.releaseTransferLoaders();
        if (!pending.sourceCapture) RemoteCameraSession.close(pending.player, pending.sequence);
        if (pending.player.getServer().getPlayerList().getPlayer(pending.player.getUUID()) == pending.player) {
            PacketDistributor.sendToPlayer(pending.player,
                    new RemoteSceneStopS2C(pending.sequence));
        }
    }

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
                    pending.scene = pending.scene.withProjectedPlayers(pending.playersInFrame.stream()
                            .map(ServerPlayer::getUUID).toList());
                    RemoteCameraSession.flushCapture(pending.player);
                }
                pending.exposureStartedAt = System.nanoTime();
                pending.state = State.EXPOSING;
                invoker.shuttershadow$invokeTakePhoto(pending.stand, pending.player, pending.camera);
                if (pending.discardImage) {
                    pending.state = State.FINISHING;
                    playCompletionSound(pending);
                    if (pending.remote != null) {
                        DimensionFilmCapture.teleportStandPlayersAfterPhoto(
                                pending.remote, pending.camera, pending.playersInFrame);
                    }
                    pending.releaseTransferLoaders();
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

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PENDING.values().removeIf(pending -> {
            if (pending.player != player) return false;
            discardPreparedShutter(pending);
            pending.releaseTransferLoaders();
            if (!pending.sourceCapture) RemoteCameraSession.close(player, pending.sequence);
            return true;
        });
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ARMED.clear();
        PENDING.values().forEach(pending -> {
            discardPreparedShutter(pending);
            pending.releaseTransferLoaders();
            if (!pending.sourceCapture) RemoteCameraSession.close(pending.player, pending.sequence);
        });
        PENDING.clear();
    }
}

package com.xfw.shuttershadow.camera;

import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.item.MobDimensionFilmRollItem;

import com.xfw.shuttershadow.network.RemoteStandPreparation;
import com.xfw.shuttershadow.api.CameraCaptureContext;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.item.camera.Attachment;
import io.github.mortuusars.exposure.world.level.storage.ExposureRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.xfw.shuttershadow.api.ChunkLoading;
import com.xfw.shuttershadow.api.ChunkLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;

/** 按拍摄计划选择目标生物，上传或免成片完成后执行传送并释放保活区块。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class MobDimensionFilmCapture {
    private static final long UPLOAD_TIMEOUT_MILLIS = 120_000L;
    /** 摄影师UUID与Exposure照片标识组成的事务键。 */
    private record PendingKey(UUID player, String exposureId) {
    }

    private static final Map<PendingKey, List<Pending>> PENDING = new HashMap<>();

    /** 固定目标生物UUID及映射回源维度的位置。 */
    private static final class Pending {
        private final ServerPlayer photographer;
        private final ServerLevel source;
        private final ServerLevel target;
        private final UUID entityId;
        private final Vec3 destination;
        private final ItemStack camera;
        private final CameraCaptureContext context;
        private ChunkLoader loader;
        private long expiresAt = System.currentTimeMillis() + UPLOAD_TIMEOUT_MILLIS;

        /** 保存源/目标世界、实体UUID和反向映射目的地，注册半径0的全局保活loader。 */
        private Pending(ServerPlayer photographer, RemoteCaptureContext remote, LivingEntity entity, ItemStack camera) {
            this.photographer = photographer;
            this.camera = camera;
            context = CameraCaptureEvents.context(camera);
            source = CameraCaptureEvents.mobDestinationLevel(camera, (ServerLevel) remote.source().asHolderEntity().level());
            target = remote.level();
            entityId = entity.getUUID();
            destination = CameraCaptureEvents.mobDestination(camera, remote, entity);
            BlockPos block = entity.blockPosition();
            loader = new ChunkLoader(target.dimension(), block.getX() >> 4, block.getZ() >> 4, 0);
            // 只保活选中实体的区块，避免关取景器/结束截图后上传尚未完成就卸载实体。
            ChunkLoading.addGlobalChunkLoader(photographer.getServer(), loader);
        }

        /** 移除当前全局loader。 */
        private void release() {
            if (loader != null) {
                ChunkLoading.removeGlobalChunkLoader(photographer.getServer(), loader);
                loader = null;
            }
        }

        /** 按UUID在目标世界找到实体，跨区块时先注册新半径0 loader，再释放旧loader。 */
        private void followEntity() {
            Entity entity = target.getEntity(entityId);
            if (entity == null) return;
            BlockPos block = entity.blockPosition();
            int x = block.getX() >> 4;
            int z = block.getZ() >> 4;
            if (x == loader.x() && z == loader.z()) return;
            ChunkLoader replacement = new ChunkLoader(target.dimension(), x, z, 0);
            ChunkLoading.addGlobalChunkLoader(photographer.getServer(), replacement);
            release();
            loader = replacement;
        }

        /** 确认仍是存活非玩家LivingEntity，再调用无缝实体API。 */
        private void transfer() {
            try {
                Entity entity = target.getEntity(entityId);
                if (!(entity instanceof LivingEntity living) || entity instanceof Player
                        || !living.isAlive() || living.isRemoved()) return;
                Entity moved = CameraTransfers.teleportEntity(entity, source, destination, camera);
                if (moved == null || moved.isRemoved()) {
                    Shuttershadow.LOGGER.warn("Creature film could not transfer entity {} from {} to {}.",
                            entityId, target.dimension().location(), source.dimension().location());
                }
            } catch (RuntimeException exception) {
                Shuttershadow.LOGGER.error("Failed to transfer photographed entity {}.", entityId, exception);
            } finally {
                release();
            }
        }
    }

    /** 禁止实例化此工具类。 */
    private MobDimensionFilmCapture() {
    }

    /** 判断FILM附件物品是否为MobDimensionFilmRollItem。 */
    public static boolean hasMobDimensionFilm(ItemStack camera) {
        return Attachment.FILM.get(camera).getForReading().getItem() instanceof MobDimensionFilmRollItem;
    }

    /** 根据固定拍摄执行者、计划和本模组对象选择事件建立生物事务。 */
    public static void prepare(ItemStack camera, Frame frame) {
        RemoteCaptureContext remote = CameraCaptureEvents.mobContext(camera);
        if (remote == null) return;
        ServerPlayer photographer = CameraCaptureEvents.context(camera).getExecutor();
        List<LivingEntity> selected = CameraCaptureEvents.selectMobs(camera, remote);
        if (selected.isEmpty()) return;
        String id = frame.identifier().id();
        PendingKey key = new PendingKey(photographer.getUUID(), id);
        List<Pending> prepared = new java.util.ArrayList<>();
        try {
            for (LivingEntity entity : selected) {
                if (entity.level() == remote.level()) prepared.add(new Pending(photographer, remote, entity, camera));
            }
        } catch (RuntimeException exception) {
            prepared.forEach(Pending::release); throw exception;
        }
        List<Pending> previous = PENDING.put(key, List.copyOf(prepared));
        if (previous != null) previous.forEach(Pending::release);
    }

    /** 如果存在属于该摄影师的Pending，改写Exposure仓库上传完成回调。 */
    public static boolean expectUpload(ExposureRepository repository, ServerPlayer player, String id) {
        PendingKey key = new PendingKey(player.getUUID(), id);
        List<Pending> pending = PENDING.get(key);
        if (pending == null || pending.isEmpty() || pending.getFirst().photographer != player) return false;
        repository.expect(player, id, (uploadedPlayer, uploadedId) -> {
            PendingKey uploadedKey = new PendingKey(uploadedPlayer.getUUID(), uploadedId);
            if (PENDING.remove(uploadedKey, pending)) transfer(pending);
        });
        return true;
    }

    /** 免曝光上传路径按摄影师和照片ID确认Pending后立即移除、传送。 */
    public static void completeWithoutUpload(ServerPlayer player, String id) {
        PendingKey key = new PendingKey(player.getUUID(), id);
        List<Pending> pending = PENDING.get(key);
        if (pending != null && !pending.isEmpty() && pending.getFirst().photographer == player && PENDING.remove(key, pending)) {
            transfer(pending);
        }
    }

    /** 各对象独立传送，使用原照片上下文而不是该相机后来拍摄的计划。 */
    private static void transfer(List<Pending> pending) {
        for (Pending candidate : pending) {
            if (candidate.context == null) candidate.release();
            else if (!CameraCaptureEvents.withContext(candidate.context, candidate::transfer)) candidate.release();
        }
    }

    /** 取消指定照片Pending并释放保活loader。 */
    public static void cancelPending(ServerPlayer player, String exposureId) {
        PendingKey key = new PendingKey(player.getUUID(), exposureId);
        List<Pending> pending = PENDING.get(key);
        if (pending != null && !pending.isEmpty() && pending.getFirst().photographer == player && PENDING.remove(key, pending)) {
            pending.forEach(Pending::release);
        }
    }

    /** 服务端tick续期仍由支架准备事务拥有的照片，清理超时项，否则跟随目标生物移动其保活区块。 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(entry -> {
            List<Pending> candidates = entry.getValue();
            if (candidates.isEmpty()) return true;
            Pending pending = candidates.getFirst();
            // 支架照片尚未完成时保留候选，仅在截图结束后计上传清理时限。
            if (RemoteStandPreparation.ownsExposure(entry.getKey().exposureId())) {
                candidates.forEach(candidate -> candidate.expiresAt = now + UPLOAD_TIMEOUT_MILLIS);
            }
            if (now >= pending.expiresAt) {
                candidates.forEach(Pending::release);
                return true;
            }
            candidates.forEach(Pending::followEntity);
            return false;
        });
    }

    /** 登出时释放属于该摄影师的全部Pending。 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.values().removeIf(pending -> {
            if (pending.isEmpty() || pending.getFirst().photographer != event.getEntity()) return false;
            pending.forEach(Pending::release);
            return true;
        });
    }

    /** 停止服务端时释放全部loader并清空Pending。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.values().forEach(pending -> pending.forEach(Pending::release));
        PENDING.clear();
    }
}

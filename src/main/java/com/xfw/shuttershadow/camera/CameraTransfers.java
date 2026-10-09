package com.xfw.shuttershadow.camera;

import com.xfw.shuttershadow.api.CameraCaptureContext;
import com.xfw.shuttershadow.api.SeamlessTeleportation;
import com.xfw.shuttershadow.api.event.CameraTransferEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/** 为相机选中的单个主体发布前后事件，实际移动由无缝API执行。 */
public final class CameraTransfers {
    /** 禁止建立实例。 */
    private CameraTransfers() {}

    /** 发布单次主体事件并返回无缝API确认成功的实体。 */
    public static @Nullable Entity teleportEntity(Entity entity, ServerLevel targetLevel,
                                                  Vec3 targetPosition, ItemStack camera) {
        return teleportEntity(entity, targetLevel, targetPosition, camera, () -> {});
    }

    /** 事件接受后移动主体，仅在成功时执行相机控制清理。 */
    private static @Nullable Entity teleportEntity(Entity entity, ServerLevel targetLevel,
            Vec3 targetPosition, ItemStack camera, Runnable afterMove) {
        if (entity == null || targetLevel == null || targetPosition == null || camera == null
                || !(entity.level() instanceof ServerLevel sourceLevel)
                || !sourceLevel.getServer().isSameThread()) return null;

        CameraCaptureContext context = CameraCaptureEvents.context(camera);
        CameraTransferEvent.Before before = new CameraTransferEvent.Before(
                context, context == null ? camera : context.getCamera(), entity,
                targetLevel, targetPosition, entity.getYRot(), entity.getXRot());
        CameraTransferEvent.Result result = CameraTransferEvent.Result.REJECTED;
        Entity moved = null;
        RuntimeException failure = null;
        float originalYaw = entity.getYRot();
        float originalPitch = entity.getXRot();
        float originalHeadYaw = entity.getYHeadRot();
        boolean rotationChanged = false;
        try {
            NeoForge.EVENT_BUS.post(before);
            if (before.isCanceled()) {
                result = CameraTransferEvent.Result.CANCELED;
                return null;
            }
            if (!isValid(entity, before)) return null;

            rotationChanged = before.getYaw() != originalYaw || before.getPitch() != originalPitch;
            boolean updateRotation = rotationChanged;
            moved = SeamlessTeleportation.teleportEntity(entity, before.getTargetLevel(),
                    before.getTargetPosition(), () -> {
                        if (updateRotation) {
                            entity.setYRot(before.getYaw());
                            entity.setXRot(before.getPitch());
                            if (before.getYaw() != originalYaw) entity.setYHeadRot(before.getYaw());
                        }
                    });
            if (moved == null) return null;
            result = CameraTransferEvent.Result.SUCCESS;
            CameraCaptureEvents.recordTransfer(camera, entity, moved);
            afterMove.run();
            return moved;
        } catch (RuntimeException exception) {
            failure = exception;
            if (result != CameraTransferEvent.Result.SUCCESS) {
                result = CameraTransferEvent.Result.FAILED;
            }
            throw exception;
        } finally {
            if (moved == null && rotationChanged && entity.level() == sourceLevel && !entity.isRemoved()) {
                entity.setYRot(originalYaw);
                entity.setXRot(originalPitch);
                entity.setYHeadRot(originalHeadYaw);
            }
            try {
                NeoForge.EVENT_BUS.post(new CameraTransferEvent.After(before, result, moved, failure));
            } catch (RuntimeException eventException) {
                if (failure == null) throw eventException;
                failure.addSuppressed(eventException);
            }
        }
    }

    /** 只在成功结果仍是原玩家实例时返回该玩家。 */
    public static @Nullable ServerPlayer teleportPlayer(ServerPlayer player, ServerLevel targetLevel,
                                                        Vec3 targetPosition, ItemStack camera) {
        return teleportEntity(player, targetLevel, targetPosition, camera) == player ? player : null;
    }

    /** 玩家实际移动成功后注销旧支架控制，拒绝或回滚时保留取景。 */
    public static @Nullable ServerPlayer teleportPlayer(ServerPlayer player, ServerLevel targetLevel,
            Vec3 targetPosition, ItemStack camera, Runnable afterMove) {
        return teleportEntity(player, targetLevel, targetPosition, camera, afterMove) == player ? player : null;
    }

    /** 拒绝过期世界、其他服务器、无效实体、越界坐标或非有限朝向。 */
    private static boolean isValid(Entity entity, CameraTransferEvent.Before before) {
        ServerLevel target = before.getTargetLevel();
        MinecraftServer server = target.getServer();
        Vec3 position = before.getTargetPosition();
        return server.isSameThread() && entity.getServer() == server
                && entity.isAlive() && !entity.isRemoved()
                && server.getLevel(target.dimension()) == target
                && SeamlessTeleportation.isValidTargetPosition(position)
                && Float.isFinite(before.getYaw()) && Float.isFinite(before.getPitch());
    }
}

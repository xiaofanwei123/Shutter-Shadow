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

    /** 事件接受并验证目标后才执行来源控制清理，取消时保留原相机。 */
    private static @Nullable Entity teleportEntity(Entity entity, ServerLevel targetLevel,
            Vec3 targetPosition, ItemStack camera, Runnable beforeMove) {
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

            beforeMove.run();

            rotationChanged = before.getYaw() != originalYaw || before.getPitch() != originalPitch;
            if (rotationChanged) {
                entity.setYRot(before.getYaw());
                entity.setXRot(before.getPitch());
                if (before.getYaw() != originalYaw) entity.setYHeadRot(before.getYaw());
            }
            moved = SeamlessTeleportation.teleportEntity(entity, before.getTargetLevel(),
                    before.getTargetPosition());
            if (moved == null) return null;
            result = CameraTransferEvent.Result.SUCCESS;
            CameraCaptureEvents.recordTransfer(camera, entity, moved);
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

    /** 玩家传送获准后执行支架控制注销，再进行原无缝移动。 */
    public static @Nullable ServerPlayer teleportPlayer(ServerPlayer player, ServerLevel targetLevel,
            Vec3 targetPosition, ItemStack camera, Runnable beforeMove) {
        return teleportEntity(player, targetLevel, targetPosition, camera, beforeMove) == player ? player : null;
    }

    /** 拒绝事件改成的过期世界、其他服务器、无效实体或非有限坐标和朝向。 */
    private static boolean isValid(Entity entity, CameraTransferEvent.Before before) {
        ServerLevel target = before.getTargetLevel();
        MinecraftServer server = target.getServer();
        Vec3 position = before.getTargetPosition();
        return server.isSameThread() && entity.getServer() == server
                && entity.isAlive() && !entity.isRemoved()
                && server.getLevel(target.dimension()) == target
                && Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                && Float.isFinite(before.getYaw()) && Float.isFinite(before.getPitch());
    }
}

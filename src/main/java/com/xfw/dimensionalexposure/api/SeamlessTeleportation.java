package com.xfw.dimensionalexposure.api;

import com.xfw.dimensionalexposure.camera.DimensionFilmCapture;
import com.xfw.dimensionalexposure.core.teleportation.ServerTeleportationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** 公开单主体无缝传送API，原载具和其他乘客留在来源世界。 */
public final class SeamlessTeleportation {
    /** 禁止实例化此工具类。 */
    private SeamlessTeleportation() {}

    /** 校验请求，只在解除骑乘及目标世界登记均成功后返回实际移动主体。 */
    public static @Nullable Entity teleportEntity(Entity entity, ServerLevel targetLevel, Vec3 targetPosition) {
        return teleportEntity(entity, targetLevel, targetPosition, () -> {});
    }

    /** 换维和下车均获准后执行移动准备；目标登记失败仍会回滚实体位置与骑乘。 */
    public static @Nullable Entity teleportEntity(Entity entity, ServerLevel targetLevel, Vec3 targetPosition,
                                                  Runnable beforeMove) {
        if (entity == null || targetLevel == null || targetPosition == null) return null;
        MinecraftServer server = targetLevel.getServer();
        if (!server.isSameThread() || entity.getServer() != server
                || entity.isRemoved() || !entity.isAlive()
                || server.getLevel(targetLevel.dimension()) != targetLevel
                || !isValidTargetPosition(targetPosition) || beforeMove == null) return null;
        // 即使位置已匹配，相机传送保护仍可拒绝本次请求。
        if (entity instanceof ServerPlayer player
                && DimensionFilmCapture.shouldBlockPortalTeleport(player)
                && !DimensionFilmCapture.isExplicitTransferInProgress(player)) return null;

        Entity moved = ServerTeleportationManager.teleportEntityGeneral(entity, targetPosition, targetLevel, beforeMove);
        return moved != null && moved.isAlive() && !moved.isRemoved()
                && moved.level() == targetLevel
                && moved.position().distanceToSqr(targetPosition) <= 1.0E-8 ? moved : null;
    }

    /** 按原版可传送坐标范围校验位置，不要求区块加载或位于建筑高度内。 */
    public static boolean isValidTargetPosition(@Nullable Vec3 position) {
        return position != null && Double.isFinite(position.x) && Double.isFinite(position.y)
                && Double.isFinite(position.z) && Level.isInSpawnableBounds(BlockPos.containing(position));
    }

    /** 调用实体入口，只在结果仍是原ServerPlayer对象时返回该玩家，否则null。 */
    public static @Nullable ServerPlayer teleportPlayer(ServerPlayer player, ServerLevel targetLevel, Vec3 targetPosition) {
        return teleportEntity(player, targetLevel, targetPosition) == player ? player : null;
    }
}

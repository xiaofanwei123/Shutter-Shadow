package com.xfw.shuttershadow.api;

import com.xfw.shuttershadow.camera.DimensionFilmCapture;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** 公开单主体无缝传送API，原载具和其他乘客留在来源世界。 */
public final class SeamlessTeleportation {
    /** 禁止实例化此工具类。 */
    private SeamlessTeleportation() {}

    /** 校验请求，只在解除骑乘及目标世界登记均成功后返回实际移动主体。 */
    public static @Nullable Entity teleportEntity(Entity entity, ServerLevel targetLevel, Vec3 targetPosition) {
        if (entity == null || targetLevel == null || targetPosition == null) return null;
        MinecraftServer server = targetLevel.getServer();
        if (!server.isSameThread() || entity.getServer() != server
                || entity.isRemoved() || !entity.isAlive()
                || server.getLevel(targetLevel.dimension()) != targetLevel
                || !Double.isFinite(targetPosition.x) || !Double.isFinite(targetPosition.y)
                || !Double.isFinite(targetPosition.z)) return null;
        // 即使位置已匹配，相机传送保护仍可拒绝本次请求。
        if (entity instanceof ServerPlayer player
                && DimensionFilmCapture.shouldBlockPortalTeleport(player)
                && !DimensionFilmCapture.isExplicitTransferInProgress(player)) return null;

        Entity moved = ServerTeleportationManager.teleportEntityGeneral(entity, targetPosition, targetLevel);
        return moved != null && moved.isAlive() && !moved.isRemoved()
                && moved.level() == targetLevel
                && moved.position().distanceToSqr(targetPosition) <= 1.0E-8 ? moved : null;
    }

    /** 调用实体入口，只在结果仍是原ServerPlayer对象时返回该玩家，否则null。 */
    public static @Nullable ServerPlayer teleportPlayer(ServerPlayer player, ServerLevel targetLevel, Vec3 targetPosition) {
        return teleportEntity(player, targetLevel, targetPosition) == player ? player : null;
    }
}

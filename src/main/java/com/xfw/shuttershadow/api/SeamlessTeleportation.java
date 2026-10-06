package com.xfw.shuttershadow.api;

import com.xfw.shuttershadow.DimensionFilmCapture;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Server-thread entry points for Shuttershadow's immediate, seamless transfers. */
public final class SeamlessTeleportation {
    private SeamlessTeleportation() {}

    /**
     * Moves an entity to a feet position in a registered level on the same server.
     * Returns {@code null} for an invalid or refused request. Ordinary entities
     * are recreated across dimensions: callers must use the returned reference.
     * This forced transfer does not fire NeoForge's cancellable dimension-travel
     * event or wait for destination chunks. A player's vehicle follows that
     * player; the vehicle's other passengers do not move with it.
     */
    public static @Nullable Entity teleportEntity(Entity entity, ServerLevel targetLevel, Vec3 targetPosition) {
        if (entity == null || targetLevel == null || targetPosition == null) return null;
        MinecraftServer server = targetLevel.getServer();
        if (!server.isSameThread() || entity.getServer() != server
                || entity.isRemoved() || !entity.isAlive()
                || server.getLevel(targetLevel.dimension()) != targetLevel
                || !Double.isFinite(targetPosition.x) || !Double.isFinite(targetPosition.y)
                || !Double.isFinite(targetPosition.z)) return null;
        // The camera protection can refuse even an already-matching position.
        if (entity instanceof ServerPlayer player
                && DimensionFilmCapture.shouldBlockPortalTeleport(player)
                && !DimensionFilmCapture.isExplicitTransferInProgress(player)) return null;

        Entity moved = ServerTeleportationManager.teleportEntityGeneral(entity, targetPosition, targetLevel);
        return moved != null && moved.isAlive() && !moved.isRemoved()
                && moved.level() == targetLevel
                && moved.position().distanceToSqr(targetPosition) <= 1.0E-8 ? moved : null;
    }

    /** Same contract as {@link #teleportEntity}; a successful player keeps its identity. */
    public static @Nullable ServerPlayer teleportPlayer(ServerPlayer player, ServerLevel targetLevel, Vec3 targetPosition) {
        return teleportEntity(player, targetLevel, targetPosition) == player ? player : null;
    }
}

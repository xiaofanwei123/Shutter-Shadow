package com.xfw.shuttershadow.core.teleportation;
// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.logging.LogUtils;
import com.xfw.shuttershadow.DimensionFilmCapture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import com.xfw.shuttershadow.core.ServerRuntimeState;
import com.xfw.shuttershadow.util.McHelper;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import com.xfw.shuttershadow.network.PacketRedirection;
import com.xfw.shuttershadow.access.IEEntity;
import com.xfw.shuttershadow.access.IEChunkMap;
import com.xfw.shuttershadow.access.IETrackedEntity;
import com.xfw.shuttershadow.access.IEServerPlayerEntity;
import com.xfw.shuttershadow.core.CoreConfig;

import java.util.HashSet;
import java.util.Set;

/** Direct dimension transfers used by the camera and entity transport API. */
public class ServerTeleportationManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Set<Entity> teleportingEntities = new HashSet<>();

    public static ServerTeleportationManager of(MinecraftServer server) {
        return ServerRuntimeState.of(server).teleportationManager;
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            of(event.getServer()).tick(event.getServer());
        });

    }

    private void tick(MinecraftServer server) {
        teleportingEntities.clear();
    }

    public void forceTeleportPlayer(
        ServerPlayer player, ResourceKey<Level> dimensionTo, Vec3 newPos
    ) {
        forceTeleportPlayer(
            player, dimensionTo, newPos, true
        );
    }

    public void forceTeleportPlayer(
        ServerPlayer player, ResourceKey<Level> dimensionTo, Vec3 newPos,
        boolean sendPacket
    ) {
        // 胶卷主动传送可通过；保护期内其他传送请求继续拦截。
        if (DimensionFilmCapture.shouldBlockPortalTeleport(player)
            && !DimensionFilmCapture.isExplicitTransferInProgress(player)) {
            return;
        }

        if (CoreConfig.SERVER_TELEPORT_LOGGING.get()) {
            LOGGER.info(
                "Force teleporting {} to {} {}",
                player, dimensionTo.location(), newPos
            );
        }

        ServerLevel fromWorld = (ServerLevel) player.level();
        ServerLevel toWorld = player.server.getLevel(dimensionTo);

        if (toWorld == null) {
            LOGGER.error(
                "Cannot teleport player {} to non-existing dimension {}",
                player, dimensionTo.location()
            );
            return;
        }
        
        if (player.level().dimension() == dimensionTo) {
            player.setPos(newPos.x, newPos.y, newPos.z);
        }
        else {
            changePlayerDimension(player, fromWorld, toWorld, newPos.add(McHelper.getEyeOffset(player)));
        }
        
        if (sendPacket) {
            player.connection.teleport(
                newPos.x,
                newPos.y,
                newPos.z,
                player.getYRot(),
                player.getXRot()
            );
        }
        
        // reset the "authentic" player position as the current position
        player.connection.resetPosition();
        

        RemoteChunkTracking.immediatelyUpdateForPlayer(player);
    }

    private void changePlayerDimension(
        ServerPlayer player,
        ServerLevel fromWorld,
        ServerLevel toWorld,
        Vec3 newEyePos
    ) {
        // avoid the player from untracking all entities when removing from the old world
        // see MixinChunkMap_E
        teleportingEntities.add(player);
        
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            ((IEServerPlayerEntity) player).ip_stopRidingWithoutTeleportRequest();
        }
        
        Vec3 oldPos = player.position();
        
        // 释放旧原版区块视野与待发送队列，此阶段数据包明确标注旧维度。
        PacketRedirection.withForceRedirect(fromWorld,
                () -> fromWorld.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION));
        ((IEEntity) player).ip_unsetRemoved();
        
        McHelper.setEyePos(player, newEyePos, newEyePos);
        McHelper.updateBoundingBox(player);
        
        player.setServerLevel(toWorld);
        
        // 原版注册会立即发送区块中心及实体包，此时客户端尚未收到跨维度位置包。
        PacketRedirection.withForceRedirect(toWorld, () -> toWorld.addDuringTeleport(player));
        
        if (vehicle != null) {
            Vec3 offset = McHelper.getVehicleOffsetFromPassenger(vehicle, player);
            Vec3 vehiclePos = player.position().add(offset);
            vehicle = teleportVehicleAcrossDimensions(
                vehicle,
                toWorld,
                vehiclePos.add(McHelper.getEyeOffset(vehicle)),
                player
            );
            McHelper.setPosAndLastTickPos(
                vehicle,
                player.position().add(offset),
                McHelper.lastTickPosOf(player).add(offset)
            );
            ((IEServerPlayerEntity) player).ip_startRidingWithoutTeleportRequest(vehicle);
            McHelper.adjustVehicle(player);
        }
        
        if (CoreConfig.SERVER_TELEPORT_LOGGING.get()) {
            LOGGER.info(
                "{} :: ({} {} {} {})->({} {} {} {})",
                player.getName().getContents(),
                fromWorld.dimension().location(),
                oldPos.x(), oldPos.y(), oldPos.z(),
                toWorld.dimension().location(),
                (int) player.getX(), (int) player.getY(), (int) player.getZ()
            );
        }
        
        
        //update advancements
        ((IEServerPlayerEntity) player).portal_worldChanged(fromWorld, oldPos);
    }

    public boolean isTeleporting(Entity entity) {
        return teleportingEntities.contains(entity);
    }

    public Entity changeEntityDimension(
        Entity entity,
        ResourceKey<Level> toDimension,
        Vec3 newEyePos
    ) {
        if (entity.getRemovalReason() != null) {
            LOGGER.error("Trying to teleport a removed entity {}", entity, new Throwable());
            return entity;
        }
        
        MinecraftServer server = entity.getServer();
        Validate.notNull(server, "server is null");

        ServerLevel toWorld = server.getLevel(toDimension);

        if (toWorld == null) {
            LOGGER.error(
                "Invalid dest dimension {} to teleport entity {} to",
                toDimension.location(), entity
            );
            return entity;
        }

        entity.unRide();
        
        Entity oldEntity = entity;
        Entity newEntity = entity.getType().create(toWorld);
        if (newEntity == null) {
            return oldEntity;
        }

        newEntity.restoreFrom(oldEntity);
        newEntity.setId(oldEntity.getId());
        McHelper.setEyePos(newEntity, newEyePos, newEyePos);
        McHelper.updateBoundingBox(newEntity);
        newEntity.setYHeadRot(oldEntity.getYHeadRot());

        // TODO check minecart item duplication
        oldEntity.remove(Entity.RemovalReason.CHANGED_DIMENSION);

        toWorld.addDuringTeleport(newEntity);

        return newEntity;
    }

    private Entity teleportVehicleAcrossDimensions(
        Entity entity,
        ServerLevel toWorld,
        Vec3 newEyePos,
        ServerPlayer rider
    ) {
        // 仅乘客保留客户端载具用于无缝切换；其余观察者必须清除原维度旧载具。
        teleportingEntities.add(entity);
        
        ServerLevel fromWorld = (ServerLevel) entity.level();
        
        Entity oldEntity = entity;
        Entity newEntity;
        newEntity = entity.getType().create(toWorld);
        Validate.isTrue(newEntity != null);
        
        newEntity.restoreFrom(oldEntity);
        newEntity.setId(oldEntity.getId());
        McHelper.setEyePos(newEntity, newEyePos, newEyePos);
        McHelper.updateBoundingBox(newEntity);
        newEntity.setYHeadRot(oldEntity.getYHeadRot());

        var tracker = ((IEChunkMap) fromWorld.getChunkSource().chunkMap)
                .ip_getEntityTrackerMap().get(oldEntity.getId());
        if (tracker != null) {
            ((IETrackedEntity) tracker).ip_stopTrackingExcept(rider);
        }
        
        oldEntity.remove(Entity.RemovalReason.CHANGED_DIMENSION);
        ((IEEntity) oldEntity).ip_unsetRemoved();
        
        // 载具在跨维度位置包之前登记，和乘客一样必须先路由至目标世界。
        PacketRedirection.withForceRedirect(toWorld, () -> toWorld.addDuringTeleport(newEntity));
        
        return newEntity;
    }

    public static Entity teleportEntityGeneral(Entity entity, Vec3 targetPos, ServerLevel targetWorld) {
        if (entity instanceof ServerPlayer serverPlayer) {
            of(serverPlayer.server).forceTeleportPlayer(
                serverPlayer, targetWorld.dimension(), targetPos
            );
            return entity;
        }
        else {
            return teleportRegularEntityTo(entity, targetWorld.dimension(), targetPos);
        }
    }

    @SuppressWarnings("unchecked")
    public static <E extends Entity> E teleportRegularEntityTo(
        E entity, ResourceKey<Level> targetDim, Vec3 targetPos
    ) {
        if (entity.level().dimension() == targetDim) {
            entity.moveTo(
                targetPos.x,
                targetPos.y,
                targetPos.z,
                entity.getYRot(),
                entity.getXRot()
            );
            entity.setYHeadRot(entity.getYRot());
            return entity;
        }
        
        return (E) of(entity.getServer()).changeEntityDimension(
            entity,
            targetDim,
            targetPos.add(McHelper.getEyeOffset(entity))
        );
    }

}

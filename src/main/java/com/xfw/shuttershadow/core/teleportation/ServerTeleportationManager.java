package com.xfw.shuttershadow.core.teleportation;


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

import java.util.HashSet;
import java.util.Set;

/** 执行各服务器的玩家、普通实体及骑乘载具传送。 */
public class ServerTeleportationManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Set<Entity> teleportingEntities = new HashSet<>();

    /** 取得服务器独立管理器。 */
    public static ServerTeleportationManager of(MinecraftServer server) {
        return ServerRuntimeState.of(server).teleportationManager;
    }

    /** 注册每游戏刻清理传送标记的回调。 */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            of(event.getServer()).tick(event.getServer());
        });

    }

    /** 清理本游戏刻的传送标记。 */
    private void tick(MinecraftServer server) {
        teleportingEntities.clear();
    }

    /** 默认发送位置包的玩家传送重载。 */
    public void forceTeleportPlayer(
        ServerPlayer player, ResourceKey<Level> dimensionTo, Vec3 newPos
    ) {
        forceTeleportPlayer(
            player, dimensionTo, newPos, true
        );
    }

    /** 校验目标与胶卷保护状态，执行玩家传送并按需发送位置包。 */
    public void forceTeleportPlayer(
        ServerPlayer player, ResourceKey<Level> dimensionTo, Vec3 newPos,
        boolean sendPacket
    ) {
        // 胶卷主动传送可通过；保护期内其他传送请求继续拦截。
        if (DimensionFilmCapture.shouldBlockPortalTeleport(player)
            && !DimensionFilmCapture.isExplicitTransferInProgress(player)) {
            return;
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
        
        // 将移动校验使用的真实位置重置为玩家当前位置。
        player.connection.resetPosition();
        

        RemoteChunkTracking.immediatelyUpdateForPlayer(player);
    }

    /** 标记传送状态，完成玩家跨维度切换与载具恢复。 */
    private void changePlayerDimension(
        ServerPlayer player,
        ServerLevel fromWorld,
        ServerLevel toWorld,
        Vec3 newEyePos
    ) {
        // 从旧世界移除玩家时，保留无缝切换所需的实体跟踪状态。
        // 对应处理位于区块映射实体跟踪混入中。
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
            Vec3 offset = player.getVehicleAttachmentPoint(vehicle);
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
        
        
        
        // 更新跨维度相关进度。
        ((IEServerPlayerEntity) player).portal_worldChanged(fromWorld, oldPos);
    }

    /** 判断实体是否正在本游戏刻内传送。 */
    public boolean isTeleporting(Entity entity) {
        return teleportingEntities.contains(entity);
    }

    /** 校验实体和目标世界，执行普通实体传送。 */
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

    /** 在目标世界重建玩家的直接载具，并同步观察者状态。 */
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

    /** 玩家走forceTeleportPlayer，其余走teleportRegularEntityTo。 */
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

    /** 同维普通实体moveTo并同步头转角，跨维把脚底目的地换成眼位传给changeEntityDimension，返回实际新对象。 */
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

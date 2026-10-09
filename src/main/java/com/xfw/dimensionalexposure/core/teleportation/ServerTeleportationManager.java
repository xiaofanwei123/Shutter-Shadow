package com.xfw.dimensionalexposure.core.teleportation;

import com.xfw.dimensionalexposure.DimensionalExposure;
import com.xfw.dimensionalexposure.api.SeamlessTeleportation;
import com.xfw.dimensionalexposure.camera.DimensionFilmCapture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.commons.lang3.Validate;
import com.xfw.dimensionalexposure.core.ServerRuntimeState;
import com.xfw.dimensionalexposure.util.McHelper;
import com.xfw.dimensionalexposure.core.chunk_loading.RemoteChunkTracking;
import com.xfw.dimensionalexposure.network.PacketRedirection;
import com.xfw.dimensionalexposure.access.IEEntity;
import com.xfw.dimensionalexposure.access.IEServerPlayerEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 执行玩家和普通实体的单主体传送，原载具与其他乘客留在来源世界。 */
public class ServerTeleportationManager {
    private final Set<Entity> teleportingEntities = new HashSet<>();

    /** 取得服务器独立管理器。 */
    public static ServerTeleportationManager of(MinecraftServer server) {
        return ServerRuntimeState.of(server).teleportationManager;
    }

    /** 注册每游戏刻清理传送标记的回调。 */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            of(event.getServer()).teleportingEntities.clear();
        });

    }

    /** 默认发送位置包的玩家传送重载。 */
    public boolean forceTeleportPlayer(
        ServerPlayer player, ResourceKey<Level> dimensionTo, Vec3 newPos
    ) {
        return forceTeleportPlayer(
            player, dimensionTo, newPos, true
        );
    }

    /** 校验目标与胶卷保护状态，执行玩家传送并按需发送位置包。 */
    public boolean forceTeleportPlayer(
        ServerPlayer player, ResourceKey<Level> dimensionTo, Vec3 newPos,
        boolean sendPacket
    ) {
        ServerLevel toWorld = player.server.getLevel(dimensionTo);
        if (toWorld == null) {
            DimensionalExposure.LOGGER.error(
                "Cannot teleport player {} to non-existing dimension {}",
                player, dimensionTo.location()
            );
            return false;
        }
        return forceTeleportPlayer(player, toWorld, newPos, sendPacket, () -> {});
    }

    /** 换维和下车均获准后执行移动准备，随后复用玩家实例移动。 */
    private boolean forceTeleportPlayer(ServerPlayer player, ServerLevel toWorld,
                                        Vec3 newPos, boolean sendPacket, Runnable beforeMove) {
        if (!SeamlessTeleportation.isValidTargetPosition(newPos)) return false;
        // 胶卷主动传送可通过；保护期内其他传送请求继续拦截。
        if (DimensionFilmCapture.shouldBlockPortalTeleport(player)
            && !DimensionFilmCapture.isExplicitTransferInProgress(player)) {
            return false;
        }


        ServerLevel fromWorld = (ServerLevel) player.level();

        if (fromWorld != toWorld && toWorld.entityManager.isLoaded(player.getUUID())) return false;
        if (fromWorld != toWorld && !CommonHooks.onTravelToDimension(player, toWorld.dimension())) return false;
        if (player.level() != fromWorld || player.isRemoved() || !player.isAlive()) return false;
        RidingState riding = RidingState.capture(player);
        try {
            if (!detach(player)) {
                riding.restore();
                return false;
            }
            beforeMove.run();
            if (fromWorld == toWorld) {
                player.setPos(newPos.x, newPos.y, newPos.z);
            } else if (!changePlayerDimension(player, fromWorld, toWorld, newPos)) {
                riding.restore();
                return false;
            }
        } catch (RuntimeException failure) {
            riding.restoreAfterFailure(failure);
            throw failure;
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
        if (fromWorld != toWorld) {
            EventHooks.firePlayerChangedDimensionEvent(player, fromWorld.dimension(), toWorld.dimension());
        }
        return true;
    }

    /** 复用玩家实例完成跨维度切换，并触发原版换维进度。 */
    private boolean changePlayerDimension(
        ServerPlayer player,
        ServerLevel fromWorld,
        ServerLevel toWorld,
        Vec3 newPos
    ) {
        Vec3 oldPos = player.position();
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        float oldHeadYaw = player.getYHeadRot();
        teleportingEntities.add(player);
        try {
            // 位置包发送前，各阶段实体与区块消息仍绑定其实际世界。
            PacketRedirection.withForceRedirect(fromWorld,
                    () -> fromWorld.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION));
            ((IEEntity) player).ip_unsetRemoved();
            McHelper.setPosAndLastTickPos(player, newPos, newPos);
            McHelper.updateBoundingBox(player);
            player.setServerLevel(toWorld);
            PacketRedirection.withForceRedirect(toWorld, () -> toWorld.addDuringTeleport(player));
            if (toWorld.getEntity(player.getUUID()) != player) {
                restorePlayer(player, fromWorld, oldPos, oldYaw, oldPitch, oldHeadYaw);
                return false;
            }
        } catch (RuntimeException failure) {
            try {
                restorePlayer(player, fromWorld, oldPos, oldYaw, oldPitch, oldHeadYaw);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        ((IEServerPlayerEntity) player).portal_worldChanged(fromWorld, oldPos);
        return true;
    }

    /** 目标登记失败后恢复来源玩家，不允许回滚再次被加入世界事件取消。 */
    private void restorePlayer(ServerPlayer player, ServerLevel fromWorld, Vec3 oldPos,
                               float yaw, float pitch, float headYaw) {
        ServerLevel currentWorld = player.serverLevel();
        if (currentWorld != fromWorld && currentWorld.getEntity(player.getUUID()) == player) {
            PacketRedirection.withForceRedirect(currentWorld,
                    () -> currentWorld.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION));
        }
        ((IEEntity) player).ip_unsetRemoved();
        player.setServerLevel(fromWorld);
        McHelper.setPosAndLastTickPos(player, oldPos, oldPos);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(headYaw);
        McHelper.updateBoundingBox(player);
        if (fromWorld.getEntity(player.getUUID()) != player) {
            PacketRedirection.withForceRedirect(fromWorld, () -> {
                Validate.isTrue(fromWorld.entityManager.addNewEntityWithoutEvent(player),
                        "Cannot restore player to source world");
                player.onAddedToLevel();
            });
        }
        teleportingEntities.remove(player);
        player.connection.resetPosition();
        RemoteChunkTracking.immediatelyUpdateForPlayer(player);
    }

    /** 只解除所选主体的关系，任何被取消的下车都会拒绝移动。 */
    private static boolean detach(Entity entity) {
        entity.stopRiding();
        if (entity.isPassenger()) return false;
        entity.ejectPassengers();
        return !entity.isVehicle();
    }

    /** 保存直接骑乘关系，拒绝移动时尝试恢复，不覆盖事件建立的新关系。 */
    private record RidingState(Entity entity, Level sourceWorld, Entity vehicle, List<Entity> passengers) {
        /** 在任何下车操作之前保存来源关系。 */
        private static RidingState capture(Entity entity) {
            return new RidingState(entity, entity.level(), entity.getVehicle(), List.copyOf(entity.getPassengers()));
        }

        /** 只恢复仍在来源世界的存活主体，重新上车继续尊重挂载事件。 */
        private void restore() {
            if (!entity.isAlive() || entity.isRemoved() || entity.level() != sourceWorld) return;
            if (vehicle != null && vehicle.isAlive() && !vehicle.isRemoved()
                    && vehicle.level() == sourceWorld && !entity.isPassenger()) {
                entity.startRiding(vehicle, true);
            }
            for (Entity passenger : passengers) {
                if (!entity.isAlive() || entity.isRemoved() || entity.level() != sourceWorld) return;
                if (passenger.isAlive() && !passenger.isRemoved() && passenger.level() == sourceWorld
                        && !passenger.isPassenger()) passenger.startRiding(entity, true);
            }
        }

        /** 保留原失败原因，恢复骑乘时的异常作为附加信息。 */
        private void restoreAfterFailure(RuntimeException failure) {
            try {
                restore();
            } catch (RuntimeException rollbackFailure) {
                if (failure != rollbackFailure) failure.addSuppressed(rollbackFailure);
            }
        }
    }

    /** 判断实体是否正在本游戏刻内传送。 */
    public boolean isTeleporting(Entity entity) {
        return teleportingEntities.contains(entity);
    }

    /** 按原实体眼高将公开眼位参数转为脚底坐标，再解析目标世界。 */
    public Entity changeEntityDimension(
        Entity entity,
        ResourceKey<Level> toDimension,
        Vec3 newEyePos
    ) {
        Vec3 newPos = newEyePos.subtract(McHelper.getEyeOffset(entity));
        if (!SeamlessTeleportation.isValidTargetPosition(newPos)) return null;
        MinecraftServer server = entity.getServer();
        Validate.notNull(server, "server is null");

        ServerLevel toWorld = server.getLevel(toDimension);

        if (toWorld == null) {
            DimensionalExposure.LOGGER.error(
                "Invalid dest dimension {} to teleport entity {} to",
                toDimension.location(), entity
            );
            return null;
        }
        return changeEntityDimension(entity, toWorld, newPos, () -> {});
    }

    /** 普通实体获准换维及下车后重建目标实例，拒绝登记时恢复来源骑乘。 */
    private Entity changeEntityDimension(Entity entity, ServerLevel toWorld,
                                          Vec3 newPos, Runnable beforeMove) {
        if (!SeamlessTeleportation.isValidTargetPosition(newPos)) return null;
        if (entity.getRemovalReason() != null) {
            DimensionalExposure.LOGGER.error("Trying to teleport a removed entity {}", entity, new Throwable());
            return null;
        }
        if (toWorld.entityManager.isLoaded(entity.getUUID())) return null;
        Level sourceWorld = entity.level();
        if (sourceWorld != toWorld && !CommonHooks.onTravelToDimension(entity, toWorld.dimension())) return null;
        if (entity.level() != sourceWorld || entity.isRemoved() || !entity.isAlive()) return null;
        Entity oldEntity = entity;
        Entity newEntity = entity.getType().create(toWorld);
        if (newEntity == null) {
            return null;
        }

        RidingState riding = RidingState.capture(oldEntity);
        try {
            if (!detach(oldEntity)) {
                riding.restore();
                return null;
            }
            beforeMove.run();
            newEntity.restoreFrom(oldEntity);
            newEntity.setId(oldEntity.getId());
            McHelper.setPosAndLastTickPos(newEntity, newPos, newPos);
            McHelper.updateBoundingBox(newEntity);
            newEntity.setYHeadRot(oldEntity.getYHeadRot());

            if (!toWorld.addFreshEntity(newEntity)) {
                riding.restore();
                return null;
            }
            // TODO check minecart item duplication
            oldEntity.remove(Entity.RemovalReason.CHANGED_DIMENSION);
            return newEntity;
        } catch (RuntimeException failure) {
            if (!oldEntity.isRemoved() && toWorld.getEntity(newEntity.getUUID()) == newEntity) {
                try {
                    newEntity.remove(Entity.RemovalReason.DISCARDED);
                } catch (RuntimeException rollbackFailure) {
                    if (failure != rollbackFailure) failure.addSuppressed(rollbackFailure);
                }
            }
            riding.restoreAfterFailure(failure);
            throw failure;
        }
    }

    /** 玩家走forceTeleportPlayer，其余走teleportRegularEntityTo。 */
    public static Entity teleportEntityGeneral(Entity entity, Vec3 targetPos, ServerLevel targetWorld) {
        return teleportEntityGeneral(entity, targetPos, targetWorld, () -> {});
    }

    /** 统一传送分派，移动准备只在标准换维和下车均获准后执行一次。 */
    public static Entity teleportEntityGeneral(Entity entity, Vec3 targetPos, ServerLevel targetWorld,
                                               Runnable beforeMove) {
        if (entity instanceof ServerPlayer serverPlayer) {
            return of(serverPlayer.server).forceTeleportPlayer(
                serverPlayer, targetWorld, targetPos, true, beforeMove
            ) ? entity : null;
        }
        else {
            return teleportRegularEntityTo(entity, targetWorld, targetPos, beforeMove);
        }
    }

    /** 解析目标世界并按脚底坐标移动普通实体，返回实际移动对象。 */
    public static <E extends Entity> E teleportRegularEntityTo(
        E entity, ResourceKey<Level> targetDim, Vec3 targetPos
    ) {
        if (!SeamlessTeleportation.isValidTargetPosition(targetPos)) return null;
        ServerLevel targetWorld = entity.level().dimension() == targetDim
                ? (ServerLevel) entity.level() : entity.getServer().getLevel(targetDim);
        if (targetWorld == null) {
            DimensionalExposure.LOGGER.error(
                "Invalid dest dimension {} to teleport entity {} to",
                targetDim.location(), entity
            );
            return null;
        }
        return teleportRegularEntityTo(entity, targetWorld, targetPos, () -> {});
    }

    /** 同维直接移动，跨维复用统一事件检查、移动准备和失败恢复流程。 */
    @SuppressWarnings("unchecked")
    private static <E extends Entity> E teleportRegularEntityTo(
        E entity, ServerLevel targetWorld, Vec3 targetPos, Runnable beforeMove
    ) {
        if (!SeamlessTeleportation.isValidTargetPosition(targetPos)) return null;
        if (entity.level() == targetWorld) {
            RidingState riding = RidingState.capture(entity);
            try {
                if (!detach(entity)) {
                    riding.restore();
                    return null;
                }
                beforeMove.run();
                entity.moveTo(
                    targetPos.x,
                    targetPos.y,
                    targetPos.z,
                    entity.getYRot(),
                    entity.getXRot()
                );
                entity.setYHeadRot(entity.getYRot());
                return entity;
            } catch (RuntimeException failure) {
                riding.restoreAfterFailure(failure);
                throw failure;
            }
        }
        
        return (E) of(entity.getServer()).changeEntityDimension(
            entity,
            targetWorld,
            targetPos, beforeMove
        );
    }

}

package com.xfw.shuttershadow.core.teleportation;

import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.camera.DimensionFilmCapture;
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
import com.xfw.shuttershadow.core.ServerRuntimeState;
import com.xfw.shuttershadow.util.McHelper;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import com.xfw.shuttershadow.network.PacketRedirection;
import com.xfw.shuttershadow.access.IEEntity;
import com.xfw.shuttershadow.access.IEServerPlayerEntity;

import java.util.HashSet;
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
        // 胶卷主动传送可通过；保护期内其他传送请求继续拦截。
        if (DimensionFilmCapture.shouldBlockPortalTeleport(player)
            && !DimensionFilmCapture.isExplicitTransferInProgress(player)) {
            return false;
        }


        ServerLevel fromWorld = (ServerLevel) player.level();
        ServerLevel toWorld = player.server.getLevel(dimensionTo);

        if (toWorld == null) {
            Shuttershadow.LOGGER.error(
                "Cannot teleport player {} to non-existing dimension {}",
                player, dimensionTo.location()
            );
            return false;
        }

        if (fromWorld != toWorld && toWorld.entityManager.isLoaded(player.getUUID())) return false;
        if (!detach(player)) return false;

        if (fromWorld == toWorld) {
            player.setPos(newPos.x, newPos.y, newPos.z);
        }
        else {
            if (!changePlayerDimension(player, fromWorld, toWorld,
                    newPos.add(McHelper.getEyeOffset(player)))) return false;
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
        return true;
    }

    /** 复用玩家实例完成跨维度切换，并触发原版换维进度。 */
    private boolean changePlayerDimension(
        ServerPlayer player,
        ServerLevel fromWorld,
        ServerLevel toWorld,
        Vec3 newEyePos
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
            McHelper.setEyePos(player, newEyePos, newEyePos);
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
        McHelper.setEyePos(player, oldPos.add(McHelper.getEyeOffset(player)),
                oldPos.add(McHelper.getEyeOffset(player)));
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
            Shuttershadow.LOGGER.error("Trying to teleport a removed entity {}", entity, new Throwable());
            return null;
        }
        
        MinecraftServer server = entity.getServer();
        Validate.notNull(server, "server is null");

        ServerLevel toWorld = server.getLevel(toDimension);

        if (toWorld == null) {
            Shuttershadow.LOGGER.error(
                "Invalid dest dimension {} to teleport entity {} to",
                toDimension.location(), entity
            );
            return null;
        }

        if (toWorld.entityManager.isLoaded(entity.getUUID())) return null;

        Entity oldEntity = entity;
        Entity newEntity = entity.getType().create(toWorld);
        if (newEntity == null) {
            return null;
        }

        newEntity.restoreFrom(oldEntity);
        newEntity.setId(oldEntity.getId());
        McHelper.setEyePos(newEntity, newEyePos, newEyePos);
        McHelper.updateBoundingBox(newEntity);
        newEntity.setYHeadRot(oldEntity.getYHeadRot());

        if (!detach(oldEntity)) return null;
        if (!toWorld.addFreshEntity(newEntity)) return null;
        // TODO check minecart item duplication
        oldEntity.remove(Entity.RemovalReason.CHANGED_DIMENSION);

        return newEntity;
    }

    /** 玩家走forceTeleportPlayer，其余走teleportRegularEntityTo。 */
    public static Entity teleportEntityGeneral(Entity entity, Vec3 targetPos, ServerLevel targetWorld) {
        if (entity instanceof ServerPlayer serverPlayer) {
            return of(serverPlayer.server).forceTeleportPlayer(
                serverPlayer, targetWorld.dimension(), targetPos
            ) ? entity : null;
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
            if (!detach(entity)) return null;
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

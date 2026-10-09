package com.xfw.dimensionalexposure.mixin.minecraft.server;

import com.xfw.dimensionalexposure.access.IETrackedEntity;
import com.xfw.dimensionalexposure.core.chunk_loading.EntitySync;
import com.xfw.dimensionalexposure.core.chunk_loading.RemoteChunkTracking;
import com.xfw.dimensionalexposure.network.PacketRedirection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** 补充相机实体观察者，管理异维度追踪、配对和同步。 */
@Mixin(ChunkMap.TrackedEntity.class)
public abstract class MixinTrackedEntity implements IETrackedEntity {
    @Shadow @Final private ServerEntity serverEntity;
    @Shadow @Final private Entity entity;
    @Shadow @Final private Set<ServerPlayerConnection> seenBy;
    @Unique private final Set<ServerPlayerConnection> dimensionalExposure$additionalWatchers = new HashSet<>();
    /** Shadow 引用原 updatePlayer。 */
    @Shadow public abstract void updatePlayer(ServerPlayer player);
    /** Shadow 引用原 getEffectiveRange，额外观察者仍使用实体类型的原有效追踪距离。 */
    @Shadow protected abstract int getEffectiveRange();

    /** 按实体实际维度向本地及远景观察者发送更新包。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "broadcast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerPlayerConnection;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void dimensionalExposure$sendEntityUpdate(ServerPlayerConnection connection, Packet packet) {
        PacketRedirection.sendRedirectedPacket(connection.getPlayer().connection, packet, entity.level().dimension());
    }

    /** updatePlayer 开头：不同维度不执行原玩家距离删配对，已有 seenBy 转记额外观察者。 */
    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void dimensionalExposure$keepRemoteWatcher(ServerPlayer player, CallbackInfo ci) {
        if (player.level().dimension() != entity.level().dimension()) {
            if (seenBy.contains(player.connection) && dimensionalExposure$additionalWatchers.add(player.connection)) {
                EntitySync.markForUpdate((ServerLevel) entity.level());
            }
            ci.cancel();
            return;
        }
        var records = RemoteChunkTracking.getWatchRecordForChunk(
                entity.level().dimension(), entity.chunkPosition().x, entity.chunkPosition().z);
        if (records != null && dimensionalExposure$watchesAdditionalEntity(records.get(player), player, getEffectiveRange())) {
            if (seenBy.add(player.connection)) {
                serverEntity.addPairing(player);
            }
            if (dimensionalExposure$additionalWatchers.add(player.connection)) {
                EntitySync.markForUpdate((ServerLevel) entity.level());
            }
            ci.cancel();
        }
    }

    /** 刷新补充实体追踪：清理无效额外观察者及必要的旧配对，向有效记录新增配对。 */
    @Override
    public void ip_updateEntityTrackingStatus() {
        var watchRecords = RemoteChunkTracking.getWatchRecordForChunk(
                entity.level().dimension(), entity.chunkPosition().x, entity.chunkPosition().z);
        if (watchRecords == null && dimensionalExposure$additionalWatchers.isEmpty()) {
            return;
        }
        int range = getEffectiveRange();

        if (!dimensionalExposure$additionalWatchers.isEmpty()) {
            for (ServerPlayerConnection connection : new ArrayList<>(dimensionalExposure$additionalWatchers)) {
                ServerPlayer player = connection.getPlayer();
                RemoteChunkTracking.PlayerWatchRecord record = watchRecords == null ? null : watchRecords.get(player);
                if (player.level().dimension() == entity.level().dimension()) {
                    if (!dimensionalExposure$watchesAdditionalEntity(record, player, range)) {
                        dimensionalExposure$additionalWatchers.remove(connection);
                    }
                    updatePlayer(player);
                    continue;
                }
                if (!dimensionalExposure$watchesAdditionalEntity(record, player, range)) {
                    if (seenBy.remove(connection)) {
                        serverEntity.removePairing(player);
                    }
                    dimensionalExposure$additionalWatchers.remove(connection);
                }
            }
        }

        if (watchRecords != null) {
            watchRecords.forEach((player, record) -> {
                if (dimensionalExposure$watchesAdditionalEntity(record, player, range)
                        && !dimensionalExposure$additionalWatchers.contains(player.connection)) {
                    if (player.level().dimension() == entity.level().dimension()) {
                        updatePlayer(player);
                    } else {
                        if (seenBy.add(player.connection)) {
                            serverEntity.addPairing(player);
                        }
                        dimensionalExposure$additionalWatchers.add(player.connection);
                    }
                }
            });
        }
    }

    /** 检查订阅、区块发送状态、追踪距离及实体可见资格。 */
    @Unique
    private boolean dimensionalExposure$watchesAdditionalEntity(RemoteChunkTracking.PlayerWatchRecord record,
            ServerPlayer player, int range) {
        return entity != player
                && record != null && record.isValid && record.isLoadedToPlayer
                && record.distanceToSource * 16 + 8 <= range
                && entity.broadcastToPlayer(player);
    }

    /** 玩家换维后将旧配对转为相机订阅管理并重新检查可见性。 */
    @Override
    public void ip_onPlayerDimensionChange(ServerPlayer player) {
        if (seenBy.contains(player.connection) && dimensionalExposure$additionalWatchers.add(player.connection)) {
            EntitySync.markForUpdate((ServerLevel) entity.level());
        }
    }

    /** 解除全部实体配对，并清空追踪集合。 */
    @Override
    public void ip_stopTracking() {
        for (ServerPlayerConnection connection : seenBy) {
            serverEntity.removePairing(connection.getPlayer());
        }
        seenBy.clear();
        dimensionalExposure$additionalWatchers.clear();
    }
}

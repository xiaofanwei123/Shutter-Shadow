package com.xfw.shuttershadow.mixin.minecraft.server;


import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import com.xfw.shuttershadow.network.PacketRedirection;
import com.xfw.shuttershadow.util.Helper;

import java.util.List;
import java.util.Set;

/** 同步玩家登录和重生状态，并向相机观察者补发维度广播。 */
@Mixin(value = PlayerList.class, priority = 800)
public class MixinPlayerList {
    @Shadow
    @Final
    private List<ServerPlayer> players;
    
    @Shadow
    @Final
    private MinecraftServer server;
    
    /** placeNewPlayer 尾部立即刷新额外追踪并记录登录，保证登录状态/额外订阅一致。 */
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void onOnPlayerConnect(
        Connection connection, ServerPlayer player,
        CommonListenerCookie commonListenerCookie, CallbackInfo ci
    ) {
        RemoteChunkTracking.immediatelyUpdateForPlayer(player);
        
        // 用于调试。
        Helper.LOGGER.info("Player login {} {}", player.level().getGameTime(), player);
    }
    
    /** 向正在异维度观察目标世界的玩家补发维度广播包。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/resources/ResourceKey;)V",
            at = @At("TAIL"))
    private void shuttershadow$broadcastRemoteDimension(Packet<?> packet, ResourceKey<Level> dimension, CallbackInfo ci) {
        for (ServerPlayer player : players) {
            if (player.level().dimension() != dimension
                    && RemoteChunkTracking.getVisibleDimensions(player).contains(dimension)) {
                PacketRedirection.sendRedirectedMessage(player, dimension, (Packet) packet);
            }
        }
    }

    /** 重生复制状态后更新连接中的玩家引用。 */
    @Redirect(
        method = "respawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;restoreFrom(Lnet/minecraft/server/level/ServerPlayer;Z)V"
        )
    )
    private void onRestoreFrom(ServerPlayer newPlayer, ServerPlayer that, boolean keepEverything) {
        newPlayer.restoreFrom(that, keepEverything);
        
        newPlayer.connection.player = newPlayer;
    }
    
    /** 位置广播尾部按消息位置区块取观察记录，排除指定玩家和同维度原生接收者，对范围内有效额外观察者发重定向包。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("TAIL"))
    private void shuttershadow$broadcastRemotePosition(@Nullable Player excludingPlayer,
            double x, double y, double z, double distance, ResourceKey<Level> dimension,
            Packet<?> packet, CallbackInfo ci) {
        ChunkPos pos = new ChunkPos(BlockPos.containing(x, y, z));
        var records = RemoteChunkTracking.getWatchRecordForChunk(dimension, pos.x, pos.z);
        if (records == null) return;
        for (var record : records.values()) {
            ServerPlayer player = record.player;
            if (player != excludingPlayer && player.level().dimension() != dimension
                    && RemoteChunkTracking.isPlayerWatchingChunkWithinRadius(player, dimension,
                            pos.x, pos.z, (int) distance + 16)) {
                PacketRedirection.sendRedirectedMessage(player, dimension, (Packet) packet);
            }
        }
    }
}

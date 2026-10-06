package com.xfw.shuttershadow.mixin.minecraft.server;


import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import com.xfw.shuttershadow.network.MiscNetworking;

/** 服务端 PlayerList 的原玩家实例清理及维度数字 ID 初始化，作用于正常连接生命周期。 */
@Mixin(PlayerList.class)
public class MixinPlayerList_Misc {
    /** respawn 开头从额外区块与实体追踪移除 oldPlayer，避免同 UUID 新实例接管后旧对象还拥有订阅。 */
    @Inject(
        method = "respawn",
        at = @At("HEAD")
    )
    private void onPlayerRespawn(
        ServerPlayer oldPlayer, boolean bl, Entity.RemovalReason removalReason,
        CallbackInfoReturnable<ServerPlayer> cir
    ) {
        RemoteChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(oldPlayer);
    }

    /** remove 开头清掉离线玩家所有相机区块/实体追踪，避免票据和观察者残留。 */
    @Inject(
        method = "remove",
        at = @At("HEAD")
    )
    private void onPlayerDisconnect(ServerPlayer player, CallbackInfo ci) {
        RemoteChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player);
    }

    /** 玩家登录时先同步维度编号，保证后续远景数据包能够解码。 */
    @Inject(
        method = "placeNewPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundChangeDifficultyPacket;<init>(Lnet/minecraft/world/Difficulty;Z)V"
        )
    )
    private void onConnectionEstablished(
        Connection connection,
        ServerPlayer player,
        CommonListenerCookie commonListenerCookie,
        CallbackInfo ci
    ) {
        player.connection.send(
            MiscNetworking.DimIdSyncPacket.createPacket(player.server)
        );
    }
}

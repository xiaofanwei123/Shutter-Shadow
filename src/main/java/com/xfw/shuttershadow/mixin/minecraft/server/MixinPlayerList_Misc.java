package com.xfw.shuttershadow.mixin.minecraft.server;
// Shuttershadow phase seven: relocated into the camera core.

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

@Mixin(PlayerList.class)
public class MixinPlayerList_Misc {
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

    @Inject(
        method = "remove",
        at = @At("HEAD")
    )
    private void onPlayerDisconnect(ServerPlayer player, CallbackInfo ci) {
        RemoteChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player);
    }

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

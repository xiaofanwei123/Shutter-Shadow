package com.xfw.shuttershadow.mixin.minecraft.server;

import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 保留原版发送、节流和回执，仅避免卸载仍被相机订阅的源区块。 */
@Mixin(PlayerChunkSender.class)
public class MixinPlayerChunkSender {
    @Redirect(method = "dropChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void shuttershadow$keepCameraChunk(ServerGamePacketListenerImpl connection, Packet<?> packet,
            ServerPlayer player, ChunkPos pos) {
        if (!RemoteChunkTracking.isPlayerWatchingChunk(player, player.level().dimension(), pos.x, pos.z)) {
            connection.send(packet);
        }
    }
}

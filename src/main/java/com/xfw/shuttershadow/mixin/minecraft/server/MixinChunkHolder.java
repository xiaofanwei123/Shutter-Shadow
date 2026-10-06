package com.xfw.shuttershadow.mixin.minecraft.server;

import com.xfw.shuttershadow.network.PacketRedirection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChunkHolder.class)
public class MixinChunkHolder {
    @Shadow @Final private LevelHeightAccessor levelHeightAccessor;

    /** 仅混合了异维度观察者时分流；普通本维度广播完全走原版。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$routeRemoteUpdates(List<ServerPlayer> players, Packet packet, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) levelHeightAccessor;
        if (players.stream().noneMatch(player -> player.level() != level)) return;
        for (ServerPlayer player : players) {
            PacketRedirection.sendRedirectedPacket(player.connection, packet, level.dimension());
        }
        ci.cancel();
    }
}

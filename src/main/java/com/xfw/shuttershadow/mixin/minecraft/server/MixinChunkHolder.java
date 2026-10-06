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

/** 让区块更新广播同时正确送达本地玩家和异维度相机观察者。 */
@Mixin(ChunkHolder.class)
public class MixinChunkHolder {
    @Shadow @Final private LevelHeightAccessor levelHeightAccessor;

    /** 广播列表包含远景观察者时，按区块所属维度发送更新。 */
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

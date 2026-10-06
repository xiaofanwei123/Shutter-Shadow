package com.xfw.shuttershadow.mixin.minecraft.server;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.xfw.shuttershadow.network.PacketRedirection;

@Mixin(value = ServerEntity.class, priority = 1200)
public abstract class MixinServerEntity {
    @Shadow
    @Final
    private Entity entity;
    
    @Redirect(
        method = {"removePairing", "addPairing"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private void shuttershadow$sendEntityPairingPacket(
        ServerGamePacketListenerImpl networkHandler,
        Packet<ClientGamePacketListener> packet
    ) {
        PacketRedirection.sendRedirectedPacket(networkHandler, packet, entity.level().dimension());
    }
    
}

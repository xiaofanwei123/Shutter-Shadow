package com.xfw.shuttershadow.mixin.minecraft.common;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.network.PacketRedirection;

@Mixin(ClientboundCustomPayloadPacket.class)
public class MixinClientboundCustomPayloadPacket {
    
    @Shadow
    @Final
    private CustomPacketPayload payload;

    // this is run before Fabric API try to handle the packet
    @Inject(
        method = "handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onHandle(ClientCommonPacketListener clientCommonPacketListener, CallbackInfo ci) {
        if (payload instanceof PacketRedirection.Payload redirectPayload) {
            if (clientCommonPacketListener instanceof ClientGamePacketListener clientGamePacketListener) {
                redirectPayload.handle(clientGamePacketListener);
            }
            
            ci.cancel();
        }
    }
}

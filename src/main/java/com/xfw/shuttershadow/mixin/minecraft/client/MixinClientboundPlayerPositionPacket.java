package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.access.IEPlayerPositionLookS2CPacket;
import com.xfw.shuttershadow.network.CoreNetworkHandshake;

@Mixin(ClientboundPlayerPositionPacket.class)
public class MixinClientboundPlayerPositionPacket {
    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
    private void onRead(FriendlyByteBuf buf, CallbackInfo ci) {
        if (CoreNetworkHandshake.doesServerHaveDimensionRuntime()) {
            ResourceKey<Level> playerDimension = buf.readResourceKey(Registries.DIMENSION);
            ((IEPlayerPositionLookS2CPacket) this).ip_setPlayerDimension(playerDimension);
        }
    }
    
}

package com.xfw.dimensionalexposure.mixin.minecraft.client;


import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.dimensionalexposure.access.IEPlayerPositionLookS2CPacket;
import com.xfw.dimensionalexposure.network.CoreNetworkHandshake;

/** 在服务器支持内核协议时读取位置包的扩展维度字段。 */
@Mixin(ClientboundPlayerPositionPacket.class)
public class MixinClientboundPlayerPositionPacket {
    /** 读取扩展位置包的目标维度，仅对支持内核协议的服务器启用。 */
    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
    private void onRead(FriendlyByteBuf buf, CallbackInfo ci) {
        if (CoreNetworkHandshake.doesServerHaveDimensionRuntime()) {
            ResourceKey<Level> playerDimension = buf.readResourceKey(Registries.DIMENSION);
            ((IEPlayerPositionLookS2CPacket) this).ip_setPlayerDimension(playerDimension);
        }
    }
    
}

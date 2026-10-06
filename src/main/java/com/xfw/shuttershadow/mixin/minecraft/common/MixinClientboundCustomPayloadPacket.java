package com.xfw.shuttershadow.mixin.minecraft.common;


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

/** 拦截并处理携带目标维度的重定向数据包。 */
@Mixin(ClientboundCustomPayloadPacket.class)
public class MixinClientboundCustomPayloadPacket {
    
    @Shadow
    @Final
    private CustomPacketPayload payload;

    // 在加载器处理自定义数据包之前拦截重定向数据。
    /** 拦截维度重定向数据包，并在对应客户端世界中处理。 */
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

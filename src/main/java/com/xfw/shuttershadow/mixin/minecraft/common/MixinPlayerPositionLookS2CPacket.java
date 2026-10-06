package com.xfw.shuttershadow.mixin.minecraft.common;


import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.access.IEPlayerPositionLookS2CPacket;

/** 为无缝传送位置包提供维度字段存储和编码能力。 */
@Mixin(ClientboundPlayerPositionPacket.class)
public class MixinPlayerPositionLookS2CPacket implements IEPlayerPositionLookS2CPacket {
    private ResourceKey<Level> playerDimension;
    
    /** 读取附加 playerDimension 字段，供客户端位置包在正确世界执行。 */
    @Override
    public ResourceKey<Level> ip_getPlayerDimension() {
        return playerDimension;
    }
    
    /** 写入 playerDimension，服务端发送位置校正前设置。 */
    @Override
    public void ip_setPlayerDimension(ResourceKey<Level> dimension) {
        playerDimension = dimension;
    }
    
    /** write 返回后把 playerDimension ResourceKey 附在原版位置包末尾。 */
    @Inject(method = "Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;write(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
    private void onWrite(FriendlyByteBuf buf, CallbackInfo ci) {
        buf.writeResourceKey(playerDimension);
    }
}

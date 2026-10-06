package com.xfw.shuttershadow.mixin.minecraft.client;

import com.xfw.shuttershadow.access.IEPlayerMoveC2SPacket;
import com.xfw.shuttershadow.network.CoreNetworkHandshake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 只在服务器支持相机维度协议时，为四种移动包追加维度字段。 */
@Mixin({ServerboundMovePlayerPacket.Pos.class, ServerboundMovePlayerPacket.PosRot.class,
        ServerboundMovePlayerPacket.Rot.class, ServerboundMovePlayerPacket.StatusOnly.class})
public class MixinServerboundMovePlayerPacketWrite {
    @Inject(method = "write", at = @At("RETURN"))
    private void shuttershadow$writeDimension(FriendlyByteBuf buf, CallbackInfo ci) {
        if (!CoreNetworkHandshake.doesServerHaveDimensionRuntime()) {
            return;
        }

        ResourceKey<Level> playerDimension = ((IEPlayerMoveC2SPacket) this).ip_getPlayerDimension();
        Validate.notNull(playerDimension, "player dimension is null");
        buf.writeResourceKey(playerDimension);
    }
}

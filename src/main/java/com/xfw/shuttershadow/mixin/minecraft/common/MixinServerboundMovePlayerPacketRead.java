package com.xfw.shuttershadow.mixin.minecraft.common;

import com.xfw.shuttershadow.access.IEPlayerMoveC2SPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 在服务端读取四种移动包的扩展维度字段。 */
@Mixin({ServerboundMovePlayerPacket.Pos.class, ServerboundMovePlayerPacket.PosRot.class,
        ServerboundMovePlayerPacket.Rot.class, ServerboundMovePlayerPacket.StatusOnly.class})
public class MixinServerboundMovePlayerPacketRead {
    /** 从移动包末尾读取并保存玩家维度。 */
    @Inject(method = "read", at = @At("RETURN"))
    private static void shuttershadow$readDimension(FriendlyByteBuf buf,
            CallbackInfoReturnable<ServerboundMovePlayerPacket> cir) {
        ResourceKey<Level> playerDimension = buf.readResourceKey(Registries.DIMENSION);
        ((IEPlayerMoveC2SPacket) cir.getReturnValue()).ip_setPlayerDimension(playerDimension);
    }
}

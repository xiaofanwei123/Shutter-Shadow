package com.xfw.shuttershadow.mixin.minecraft.server;


import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.xfw.shuttershadow.network.PacketRedirection;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;

/** 在指定维度作用域中包装服务器消息，其余发送沿用原版。 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class MixinServerGamePacketListenerImpl_Redirect {
    @Shadow @Final protected MinecraftServer server;
    
    /** 修改 send 开头 packet：没有强制维度直接返回原包。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @ModifyVariable(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Packet modifyPacket(Packet originalPacket) {
        if (PacketRedirection.getForceRedirectDimension() == null) {
            return originalPacket;
        }

        // 普通本维度包直接走原版；无缝切换期间客户端还可能停留在旧世界。
        if ((Object) this instanceof ServerGamePacketListenerImpl listener
                && listener.player.level().dimension() == PacketRedirection.getForceRedirectDimension()
                && !ServerTeleportationManager.of(server).isTeleporting(listener.player)) {
            return originalPacket;
        }
        
        return PacketRedirection.createRedirectedMessage(
            server,
            PacketRedirection.getForceRedirectDimension(),
            originalPacket
        );
    }
    
}

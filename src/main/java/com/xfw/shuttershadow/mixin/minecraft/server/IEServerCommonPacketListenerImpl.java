package com.xfw.shuttershadow.mixin.minecraft.server;


import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 服务端 ServerCommonPacketListenerImpl 连接访问器，供消息路由和连接能力判断使用。 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface IEServerCommonPacketListenerImpl {
    /** Accessor 返回 connection 实际引用，不发送消息。 */
    @Accessor("connection")
    Connection ip_getConnection();
}

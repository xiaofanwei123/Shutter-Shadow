package com.xfw.shuttershadow.mixin.minecraft.server;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerCommonPacketListenerImpl.class)
public interface IEServerCommonPacketListenerImpl {
    @Accessor("connection")
    Connection ip_getConnection();
}

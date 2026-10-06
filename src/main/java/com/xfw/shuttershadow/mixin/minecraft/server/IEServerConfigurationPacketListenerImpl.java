package com.xfw.shuttershadow.mixin.minecraft.server;
// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public interface IEServerConfigurationPacketListenerImpl {
    @Accessor("gameProfile")
    GameProfile ip_getGameProfile();
}

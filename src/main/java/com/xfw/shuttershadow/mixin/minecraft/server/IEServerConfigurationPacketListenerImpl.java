package com.xfw.shuttershadow.mixin.minecraft.server;


import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 提供配置阶段玩家身份，供内核协议握手使用。 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public interface IEServerConfigurationPacketListenerImpl {
    /** Accessor 返回 gameProfile，握手仍由 CoreNetworkHandshake 执行。 */
    @Accessor("gameProfile")
    GameProfile ip_getGameProfile();
}

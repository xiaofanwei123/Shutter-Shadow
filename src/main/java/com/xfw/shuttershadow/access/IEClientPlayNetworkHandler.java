package com.xfw.shuttershadow.access;

import net.minecraft.client.multiplayer.ClientLevel;

/** MixinClientPacketListener实现的网络世界引用桥。 */
public interface IEClientPlayNetworkHandler {
    /** 临时处理目标包或真实传送时设ClientPacketListener.level。 */
    void ip_setWorld(ClientLevel world);
}

package com.xfw.shuttershadow.access;

import net.minecraft.client.multiplayer.ClientLevel;

/** MixinAbstractClientPlayer实现的clientLevel内部字段桥，客户端真实传送使用。 */
public interface IEAbstractClientPlayer {
    /** 把AbstractClientPlayer的clientLevel引用换为目标ClientLevel。 */
    void ip_setClientLevel(ClientLevel clientWorld);
}

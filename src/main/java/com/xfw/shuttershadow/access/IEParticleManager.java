package com.xfw.shuttershadow.access;

import net.minecraft.client.multiplayer.ClientLevel;

/** MixinParticleEngine实现的当前粒子世界桥，粒子本身另持有所属世界。 */
public interface IEParticleManager {
    /** 切ParticleEngine.level用于远场tick或真实换世界。 */
    void ip_setWorld(ClientLevel world);
}

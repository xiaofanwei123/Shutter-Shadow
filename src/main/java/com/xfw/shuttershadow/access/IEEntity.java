package com.xfw.shuttershadow.access;

import net.minecraft.world.level.Level;

/** MixinEntity实现的跨维度实体原位迁移桥，客户端玩家/载具及服务端特殊迁移使用。 */
public interface IEEntity {
    /** 清实体removed原因恢复可用状态。 */
    void ip_unsetRemoved();

    /** 直接替换实体Level引用。 */
    void ip_setWorld(Level world);
}

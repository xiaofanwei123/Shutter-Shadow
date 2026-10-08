package com.xfw.shuttershadow.access;

import net.minecraft.world.level.Level;

/** 供无缝传送切换玩家世界和恢复移除状态的访问接口。 */
public interface IEEntity {
    /** 清实体removed原因恢复可用状态。 */
    void ip_unsetRemoved();

    /** 直接替换实体Level引用。 */
    void ip_setWorld(Level world);
}

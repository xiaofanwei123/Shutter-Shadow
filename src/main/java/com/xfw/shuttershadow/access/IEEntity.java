package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.world.level.Level;

/** 胶卷直接换维所需的实体状态访问接口。 */
public interface IEEntity {
    void ip_unsetRemoved();

    void ip_setWorld(Level world);
}

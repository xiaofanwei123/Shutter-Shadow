package com.xfw.dimensionalexposure.access;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** 提供无缝换维所需的原版玩家进度调用桥。 */
public interface IEServerPlayerEntity {
    
    /** 更新原版换维进度及下界往返位置。 */
    void portal_worldChanged(ServerLevel fromWorld, Vec3 fromPos);
}

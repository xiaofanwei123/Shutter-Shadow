package com.xfw.dimensionalexposure.access;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** 服务端位置同步包扩展接口。 */
public interface IEPlayerPositionLookS2CPacket {
    /** 取得位置包目标维度。 */
    ResourceKey<Level> ip_getPlayerDimension();
    
    /** 设置位置包目标维度。 */
    void ip_setPlayerDimension(ResourceKey<Level> dimension);
}

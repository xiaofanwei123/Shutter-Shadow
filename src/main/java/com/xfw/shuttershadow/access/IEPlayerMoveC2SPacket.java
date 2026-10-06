package com.xfw.shuttershadow.access;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** 玩家移动包扩展接口。 */
public interface IEPlayerMoveC2SPacket {
    /** 取得包携带的玩家维度。 */
    ResourceKey<Level> ip_getPlayerDimension();
    
    /** 写包携带的玩家维度。 */
    void ip_setPlayerDimension(ResourceKey<Level> dim);
}

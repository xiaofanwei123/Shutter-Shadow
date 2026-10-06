package com.xfw.shuttershadow.access;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.*;

/** MixinChunkMap_C实现的服务端区块/实体跟踪内部桥。 */
public interface IEChunkMap {
    
    /** 取得所属ServerLevel。 */
    ServerLevel ip_getWorld();
    
    
    /** 按packed区块坐标取得已有ChunkHolder。 */
    ChunkHolder ip_getChunkHolder(long chunkPosLong);
    
    /** 在玩家对象移除/替换时清除本世界tracked entities中的观察者。 */
    void ip_onPlayerUnload(ServerPlayer oldPlayer);
    
    /** 返回原版实体数值ID→TrackedEntity映射。 */
    Int2ObjectMap<ChunkMap.TrackedEntity> ip_getEntityTrackerMap();
    
}

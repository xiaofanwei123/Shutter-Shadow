package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.*;

public interface IEChunkMap {
    
    ServerLevel ip_getWorld();
    
    
    ChunkHolder ip_getChunkHolder(long chunkPosLong);
    
    void ip_onPlayerUnload(ServerPlayer oldPlayer);
    
    Int2ObjectMap<ChunkMap.TrackedEntity> ip_getEntityTrackerMap();
    
}

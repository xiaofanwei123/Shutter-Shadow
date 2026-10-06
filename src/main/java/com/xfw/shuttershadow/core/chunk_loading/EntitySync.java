package com.xfw.shuttershadow.core.chunk_loading;

import com.xfw.shuttershadow.access.IEChunkMap;
import com.xfw.shuttershadow.access.IETrackedEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;

/** 服务端远维度实体同步刷新。 */
public final class EntitySync {
    /** 工具类私有构造器。 */
    private EntitySync() {}

    /** 遍历各世界的实体跟踪器，刷新远程观察者。 */
    public static void update(MinecraftServer server) {
        server.getProfiler().push("shuttershadow_remote_entity_tracking");
        try {
            for (ServerLevel level : server.getAllLevels()) {
                ChunkMap chunkMap = level.getChunkSource().chunkMap;
                for (ChunkMap.TrackedEntity tracked : ((IEChunkMap) chunkMap).ip_getEntityTrackerMap().values()) {
                    ((IETrackedEntity) tracked).ip_updateEntityTrackingStatus();
                }
            }
        } finally {
            server.getProfiler().pop();
        }
    }
}

package com.xfw.shuttershadow.core.chunk_loading;

import com.xfw.shuttershadow.access.IEChunkMap;
import com.xfw.shuttershadow.access.IETrackedEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;

/** 只补充相机额外实体订阅；实体 tick 和本维度跟踪由原版负责。 */
public final class EntitySync {
    private EntitySync() {}

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

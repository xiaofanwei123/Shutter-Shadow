package com.xfw.dimensionalexposure.core.chunk_loading;

import com.xfw.dimensionalexposure.access.IEChunkMap;
import com.xfw.dimensionalexposure.access.IETrackedEntity;
import com.xfw.dimensionalexposure.core.ServerRuntimeState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;

/** 服务端远维度实体同步刷新。 */
public final class EntitySync {
    /** 工具类私有构造器。 */
    private EntitySync() {}

    /** 标记无订阅世界的旧观察者，保证下次刷新解除配对。 */
    public static void markForUpdate(ServerLevel level) {
        ServerRuntimeState.of(level.getServer()).remoteEntityTrackingLevels.add(level);
    }

    /** 只刷新有订阅或待清理的世界，订阅结束后再完成一次解除配对。 */
    public static void update(MinecraftServer server) {
        server.getProfiler().push("dimensional_exposure_remote_entity_tracking");
        try {
            var pending = ServerRuntimeState.of(server).remoteEntityTrackingLevels;
            for (ServerLevel level : server.getAllLevels()) {
                boolean marked = pending.remove(level);
                boolean watching = RemoteChunkTracking.shouldLoadDimension(level.dimension());
                if (!watching && !marked) continue;
                if (watching) pending.add(level);
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

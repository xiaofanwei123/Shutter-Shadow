package com.xfw.shuttershadow.core.chunk_loading;

import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.event.ServerCleanupEvent;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongPredicate;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.commons.lang3.Validate;
import com.xfw.shuttershadow.core.CoreSettings;
import com.xfw.shuttershadow.access.IEChunkMap;
import com.xfw.shuttershadow.access.IEWorld;
import com.xfw.shuttershadow.ShuttershadowConfig;
import com.xfw.shuttershadow.util.Helper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/** 每个ServerLevel的远区块票据和按距离加载节流队列。 */
@SuppressWarnings("JavadocReference")
public class RemoteChunkTickets {
    public static final TicketType<ChunkPos> TICKET_TYPE =
        TicketType.create("shuttershadow", Comparator.comparingLong(ChunkPos::toLong));
    
    // 实例字段应避免持有服务端世界的强引用。
    public static final WeakHashMap<ServerLevel, RemoteChunkTickets> BY_DIMENSION = new WeakHashMap<>();
    
    /** 注册ServerCleanupEvent清理。 */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerCleanupEvent.class, RemoteChunkTickets::cleanup);
    }
    
    /** 区块最近登记代数及到相机源的距离。 */
    public static class ChunkTicketInfo {
        public int lastUpdateGeneration;
        public int distanceToSource;
        
        /** 保存代数和距离。 */
        public ChunkTicketInfo(int lastUpdateGeneration, int distanceToSource) {
            this.lastUpdateGeneration = lastUpdateGeneration;
            this.distanceToSource = distanceToSource;
        }
    }
    
    private final Long2ObjectOpenHashMap<ChunkTicketInfo> chunkPosToTicketInfo = new Long2ObjectOpenHashMap<>();
    
    private final ArrayList<LongLinkedOpenHashSet> chunksToAddTicketByDistance = new ArrayList<>();
    
    private final LongOpenHashSet waitingForLoading = new LongOpenHashSet();
    
    private boolean isValid = true;
    
    public final int throttlingLimit = 4;
    
    /** 私有构造器，由get按世界创建。 */
    private RemoteChunkTickets() {
    
    }
    
    // 使用世界对象而非维度编号，确保目标维度确实存在。
    /** 按ServerLevel取得/创建票据管理器。 */
    public static RemoteChunkTickets get(ServerLevel world) {
        return BY_DIMENSION.computeIfAbsent(world, k -> new RemoteChunkTickets());
    }
    
    /** 登记区块当前代数与最短距离。 */
    public void markForLoading(long chunkPos, int distanceToSource, int generation) {
        Validate.isTrue(distanceToSource >= 0);
        
        ChunkTicketInfo info = chunkPosToTicketInfo.get(chunkPos);
        
        if (info == null) {
            info = new ChunkTicketInfo(generation, distanceToSource);
            chunkPosToTicketInfo.put(chunkPos, info);
            getQueueByDistance(distanceToSource).add(chunkPos);
        }
        else {
            if (generation != info.lastUpdateGeneration) {
                info.lastUpdateGeneration = generation;
                int oldDistanceToSource = info.distanceToSource;
                info.distanceToSource = distanceToSource;
                if (getQueueByDistance(oldDistanceToSource).remove(chunkPos)) {
                    getQueueByDistance(distanceToSource).add(chunkPos);
                }
            }
            else {
                if (distanceToSource < info.distanceToSource) {
                    int oldDistanceToSource = info.distanceToSource;
                    info.distanceToSource = distanceToSource;
                    if (getQueueByDistance(oldDistanceToSource).remove(chunkPos)) {
                        getQueueByDistance(distanceToSource).add(chunkPos);
                    }
                }
            }
        }
    }
    
    /** 懒创建指定距离的LongLinkedOpenHashSet队列。 */
    private LongLinkedOpenHashSet getQueueByDistance(int distanceToSource) {
        return Helper.arrayListComputeIfAbsent(
            chunksToAddTicketByDistance,
            distanceToSource,
            LongLinkedOpenHashSet::new
        );
    }
    
    
    /** 只在有效世界线程和运行中的服务器执行。 */
    public void flushThrottling(ServerLevel world) {
        if (Thread.currentThread() != ((IEWorld) world).portal_getThread()) {
            Shuttershadow.LOGGER.error("Called in a non-server-main (or server-world) thread.", new Throwable());
            return;
        }
        
        
        if (!isValid) {
            Shuttershadow.LOGGER.error("flushing when invalid {}", world);
            return;
        }
        
        if (!world.getServer().isRunning()) {
            // 服务端正在退出保存时，禁止继续添加区块票据。
            // 相关保存死锁记录：https://github.com/iPortalTeam/ImmersivePortalsMod/issues/1455
            return;
        }
        
        DistanceManager distanceManager = getDistanceManager(world);
        
        // 清除已经完成加载的等待记录。
        waitingForLoading.removeIf((long chunkPos) -> {
            ChunkHolder chunkHolder = getChunkHolder(world, chunkPos);
            if (chunkHolder == null) {
                return true;
            }
            
            ChunkResult<LevelChunk> resultNow = chunkHolder.getEntityTickingChunkFuture()
                .getNow(null);
            
            if (resultNow == null) {
                return false;
            }
            
            if (!resultNow.isSuccess()) {
                Shuttershadow.LOGGER.error(
                    "Chunk loading failure {} {} {}",
                    world, new ChunkPos(chunkPos)
                );
            }
            
            return true;
        });
        
        // 按距离顺序处理待添加票据队列。
        for (LongLinkedOpenHashSet queue : chunksToAddTicketByDistance) {
            if (queue != null) {
                while (!queue.isEmpty()) {
                    if (waitingForLoading.size() >= throttlingLimit) {
                        return;
                    }
                    
                    long chunkPos = queue.removeFirstLong();
                    if (chunkPosToTicketInfo.containsKey(chunkPos)) {
                        addTicket(distanceManager, chunkPos);
                        
                        waitingForLoading.add(chunkPos);
                    }
                    else {
                        Shuttershadow.LOGGER.warn("Chunk {} is not in the queue", new ChunkPos(chunkPos));
                    }
                }
            }
        }
    }
    
    /** 远区块加载开关开启时为区块添加相机TICKET_TYPE及加载等级。 */
    private static void addTicket(DistanceManager distanceManager, long chunkPos) {
        if (!ShuttershadowConfig.ENABLE_REMOTE_CHUNK_LOADING.get()) {
            return;
        }
        
        ChunkPos chunkPosObj = new ChunkPos(chunkPos);
        distanceManager.addRegionTicket(
            TICKET_TYPE, chunkPosObj, getLoadingRadius(), chunkPosObj
        );
        
    }
    
    /** 按调用者提供谓词移除不再需要的记录。 */
    public void purge(
        ServerLevel world,
        LongPredicate shouldKeepLoadingFunc
    ) {
        DistanceManager distanceManager = getDistanceManager(world);
        
        chunkPosToTicketInfo.long2ObjectEntrySet().removeIf(e -> {
            long chunkPos = e.getLongKey();
            ChunkTicketInfo ticketInfo = e.getValue();
            
            boolean keepLoading = shouldKeepLoadingFunc.test(chunkPos);
            
            if (!keepLoading) {
                waitingForLoading.remove(chunkPos);
                
                boolean pendingTicketAdding = getQueueByDistance(ticketInfo.distanceToSource)
                    .remove(chunkPos);
                
                if (!pendingTicketAdding) {
                    ChunkPos chunkPosObj = new ChunkPos(chunkPos);
                    distanceManager.removeRegionTicket(
                        TICKET_TYPE, chunkPosObj, getLoadingRadius(), chunkPosObj
                    );
                }
                return true;
            }
            else {
                return false;
            }
        });
    }
    
    
    /** activeLoading时票据半径等级为2，关闭时为1（票据等级参数，不是相机取景半径）。 */
    public static int getLoadingRadius() {
        if (CoreSettings.activeLoading) {
            return 2;
        }
        else {
            return 1;
        }
    }
    
    /** 通过IEChunkMap取已有ChunkHolder。 */
    public static ChunkHolder getChunkHolder(ServerLevel world, long chunkPos) {
        return ((IEChunkMap) (world.getChunkSource()).chunkMap).ip_getChunkHolder(chunkPos);
    }
    
    /** 取得世界原版DistanceManager。 */
    public static DistanceManager getDistanceManager(ServerLevel world) {
        return world.getChunkSource().chunkMap.getDistanceManager();
    }
    
    /** 把所有管理器标失效并清世界映射，避免停止后继续flush。 */
    private static void cleanup(ServerCleanupEvent event) {
        for (RemoteChunkTickets immPtlChunkTickets : BY_DIMENSION.values()) {
            immPtlChunkTickets.isValid = false;
        }
        BY_DIMENSION.clear();
    }
}

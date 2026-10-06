package com.xfw.shuttershadow.core.chunk_loading;
import com.xfw.shuttershadow.api.ChunkLoader;


import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.EventHooks;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import com.xfw.shuttershadow.access.IEChunkMap;
import com.xfw.shuttershadow.core.VanillaRuntimeHooks;
import com.xfw.shuttershadow.network.PacketRedirection;
import com.xfw.shuttershadow.util.Helper;

import java.util.ArrayList;
import java.util.Set;

/** 保存各玩家的额外区块加载器、可见维度和远程区块发送队列。 */
@SuppressWarnings({"JavadocReference", "UnstableApiUsage"})
public class PlayerChunkLoading {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /** 每次更新玩家加载信息时清空并重新计算可见维度。 */
    public final Set<ResourceKey<Level>> visibleDimensions = new ObjectOpenHashSet<>();
    
    /** 每位玩家的额外区块加载器，通过公开接口添加和移除。 */
    public final ArrayList<ChunkLoader> additionalChunkLoaders = new ArrayList<>();
    
    public final ArrayList<ObjectArrayList<RemoteChunkTracking.PlayerWatchRecord>> distanceToPendingChunks =
        new ArrayList<>();
    
    public int loadedChunks = 0;
    
    // 区块加载通常按固定间隔更新。
    // 此标志为真时，在下一游戏刻立即更新。
    public boolean shouldUpdateImmediately = false;
    
    
    /** 记录连接类型，为跨维度区块发送选择批次配额。 */
    public final boolean isMemoryConnection;
    private float desiredChunksPerTick = 9.0F;
    private float batchQuota;
    private int unacknowledgedBatches;
    private int maxUnacknowledgedBatches = 1;
    
    /** 保存是否为内存连接，单人内存连接使用更高发送配额。 */
    public PlayerChunkLoading(boolean isMemoryConnection) {
        this.isMemoryConnection = isMemoryConnection;
    }
    
    /** 按来源距离将观察记录加入待发送队列。 */
    public void markPendingLoading(RemoteChunkTracking.PlayerWatchRecord record) {
        Helper.arrayListComputeIfAbsent(
            distanceToPendingChunks,
            record.distanceToSource,
            ObjectArrayList::new
        ).add(record);
    }
    
    /** 按批次配额发送已就绪的远程区块，并等待客户端确认。 */
    @VanillaRuntimeHooks
    public void doChunkSending(ServerPlayer serverPlayer) {
        if (this.unacknowledgedBatches >= this.maxUnacknowledgedBatches) {
            return;
        }
        
        if (isMemoryConnection) {
            this.batchQuota = 256;
        }
        else {
            this.batchQuota = Math.min(
                this.batchQuota + this.desiredChunksPerTick,
                Math.max(1.0F, this.desiredChunksPerTick)
            );
            
            if (this.batchQuota < 1.0F) {
                return;
            }
        }
        
        ServerGamePacketListenerImpl connection = serverPlayer.connection;
        MinecraftServer server = serverPlayer.server;
        
        int maxSendNum = (int) Math.floor(batchQuota);
        Validate.isTrue(maxSendNum != 0);
        
        MutableInt sentNum = new MutableInt(0);
        for (var recs : distanceToPendingChunks) {
            if (recs == null || recs.isEmpty()) {
                continue;
            }
            
            if (sentNum.getValue() >= maxSendNum) {
                break;
            }
            
            Helper.removeIfWithEarlyExit(recs, (record, shouldStop) -> {
                // 区块已卸载，移除待发送记录。
                if (!record.isValid) {
                    return true;
                }
                
                // 玩家已收到区块，移除待发送记录。
                if (record.isLoadedToPlayer) {
                    return true;
                }
                
                ServerLevel world = server.getLevel(record.dimension);
                if (world == null) {
                    LOGGER.error(
                        "Missing dimension when flushing pending loading {}",
                        record.dimension.location()
                    );
                    return true;
                }
                
                ChunkMap chunkMap = world.getChunkSource().chunkMap;
                ChunkHolder chunkHolder = ((IEChunkMap) chunkMap).ip_getChunkHolder(record.chunkPos);
                
                if (chunkHolder == null) {
                    return false; // 暂时跳过该区块。
                }
                
                LevelChunk tickingChunk = chunkHolder.getChunkToSend();
                
                // 尚未加载的区块留待后续发送。
                if (tickingChunk == null) {
                    return false;
                }
                
                // 源维度原版视野内等待原生发送，不再重复发送区块与批次。
                if (RemoteChunkTracking.isNativeChunkTracked(serverPlayer, record.dimension,
                        ChunkPos.getX(record.chunkPos), ChunkPos.getZ(record.chunkPos))) {
                    if (connection.chunkSender.isPending(record.chunkPos)) return false;
                    record.isLoadedToPlayer = true;
                    return true;
                }
                record.isLoadedToPlayer = true;

                if (sentNum.getValue() == 0) {
                    ++this.unacknowledgedBatches;
                    // 相机批次使用包装标记，由客户端独立统计和确认。
                    connection.send(PacketRedirection.createRedirectedMessage(server,
                            serverPlayer.level().dimension(), ClientboundChunkBatchStartPacket.INSTANCE));
                }
                sentNum.increment();
                
                sendChunkPacket(
                    connection, world, tickingChunk
                );
                
                if (sentNum.getValue() >= maxSendNum) {
                    shouldStop.setValue(true);
                }
                
                return true; // 从待发送列表移除。
            });
        }
        
        if (sentNum.getValue() != 0) {
            connection.send(PacketRedirection.createRedirectedMessage(server,
                    serverPlayer.level().dimension(), new ClientboundChunkBatchFinishedPacket(sentNum.getValue())));
        }
        
        this.batchQuota -= (float) sentNum.getValue();
    }
    
    /** 向目标维度发送区块及光照数据，并发布区块发送事件。 */
    @VanillaRuntimeHooks
    private static void sendChunkPacket(
        ServerGamePacketListenerImpl serverGamePacketListenerImpl,
        ServerLevel serverLevel,
        LevelChunk levelChunk
    ) {
        PacketRedirection.withForceRedirect(
            serverLevel,
            () -> {
                serverGamePacketListenerImpl.send(
                    levelChunk.getAuxLightManager(levelChunk.getPos()).sendLightDataTo(
                        new ClientboundLevelChunkWithLightPacket(levelChunk, serverLevel.getLightEngine(), null, null)
                    )
                );
                
            }
        );
        EventHooks.fireChunkSent(serverGamePacketListenerImpl.getPlayer(), levelChunk, serverLevel);
    }
    
    /** 接收远程区块批次确认，并更新客户端允许的发送速度。 */
    @VanillaRuntimeHooks
    public void onChunkBatchReceivedByClient(float clientDesiredChunkPerTick) {
        if (this.unacknowledgedBatches == 0) return;
        --this.unacknowledgedBatches;
        this.desiredChunksPerTick = !Float.isFinite(clientDesiredChunkPerTick) ?
            0.01F : Mth.clamp(clientDesiredChunkPerTick, 0.01F, 64.0F);
        if (this.unacknowledgedBatches == 0) {
            this.batchQuota = 1.0F;
        }
        
        this.maxUnacknowledgedBatches = 10;
    }
}

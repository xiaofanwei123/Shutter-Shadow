package com.xfw.shuttershadow.core.chunk_loading;
import com.xfw.shuttershadow.api.ChunkLoader;
// Shuttershadow phase seven: relocated into the camera core.

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

/**
 * Per-player chunk-loading related info.
 * Also do chunk packet sending throttling {@link PlayerChunkSender}
 */
@SuppressWarnings({"JavadocReference", "UnstableApiUsage"})
public class PlayerChunkLoading {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /**
     * Gets cleared in {@link RemoteChunkTracking#updateForPlayer(ServerPlayer)} and re-calculated
     */
    public final Set<ResourceKey<Level>> visibleDimensions = new ObjectOpenHashSet<>();
    
    /**
     * Per-player chunk loaders. Added and removed via the API.
     */
    public final ArrayList<ChunkLoader> additionalChunkLoaders = new ArrayList<>();
    
    public final ArrayList<ObjectArrayList<RemoteChunkTracking.PlayerWatchRecord>> distanceToPendingChunks =
        new ArrayList<>();
    
    public int loadedChunks = 0;
    
    // normally chunk loading will update following to an interval
    // but if this is true, it will immediately update next tick
    public boolean shouldUpdateImmediately = false;
    
    
    /**
     * Do similar functionality as {@link PlayerChunkSender},
     * but for multi-dim and non-near-loading-only
     */
    public final boolean isMemoryConnection;
    private float desiredChunksPerTick = 9.0F;
    private float batchQuota;
    private int unacknowledgedBatches;
    private int maxUnacknowledgedBatches = 1;
    
    public PlayerChunkLoading(boolean isMemoryConnection) {
        this.isMemoryConnection = isMemoryConnection;
    }
    
    /**
     * one chunk may mark pending loading multiple times with different distanceToSource
     */
    public void markPendingLoading(RemoteChunkTracking.PlayerWatchRecord record) {
        Helper.arrayListComputeIfAbsent(
            distanceToPendingChunks,
            record.distanceToSource,
            ObjectArrayList::new
        ).add(record);
    }
    
    /**
     * {@link PlayerChunkSender#sendNextChunks(ServerPlayer)}
     */
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
                // chunk unloaded, remove
                if (!record.isValid) {
                    return true;
                }
                
                // already loaded to player, remove
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
                    return false; // skip
                }
                
                LevelChunk tickingChunk = chunkHolder.getChunkToSend();
                
                // skip that chunk if not yet loaded
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
                
                return true; // remove from list
            });
        }
        
        if (sentNum.getValue() != 0) {
            connection.send(PacketRedirection.createRedirectedMessage(server,
                    serverPlayer.level().dimension(), new ClientboundChunkBatchFinishedPacket(sentNum.getValue())));
        }
        
        this.batchQuota -= (float) sentNum.getValue();
    }
    
    /**
     * {@link PlayerChunkSender#sendChunk(ServerGamePacketListenerImpl, ServerLevel, LevelChunk)}
     */
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
    
    /**
     * {@link PlayerChunkSender#onChunkBatchReceivedByClient(float)}
     */
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

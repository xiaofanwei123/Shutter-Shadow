package com.xfw.shuttershadow.core.chunk_loading;


import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.xfw.shuttershadow.util.CHelper;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.compat.SodiumInterface;
import com.xfw.shuttershadow.access.IEMinecraftClient;
import com.xfw.shuttershadow.core.VanillaRuntimeHooks;
import com.xfw.shuttershadow.core.PlatformBridge;
import com.xfw.shuttershadow.core.render.RemoteViewArea;

import java.util.function.Consumer;
import java.util.function.Function;

/** 使用双映射保存远程维度区块，支持玩家远处区块，并分别满足主线程和其他线程的访问需求。 */
// 仅供客户端使用。
/** ClientLevel的多中心客户端区块缓存。 */
@VanillaRuntimeHooks
public class RemoteClientChunkMap extends ClientChunkCache {
    private static final Logger LOGGER = LogManager.getLogger();
    
    // 大多数区块访问发生在主线程，
    // 因此使用两份映射减少同步开销。
    // 主线程访问此映射，无需同步。
    protected final Long2ObjectOpenHashMap<LevelChunk> chunkMapForMainThread =
        new Long2ObjectOpenHashMap<>();
    // 其他线程通过同步访问此映射。
    protected final Long2ObjectOpenHashMap<LevelChunk> chunkMapForOtherThreads =
        new Long2ObjectOpenHashMap<>();
    
    public final Thread mainThread;
    
    /** 构造父缓存并保存Minecraft主线程引用。 */
    public RemoteClientChunkMap(ClientLevel clientWorld) {
        super(clientWorld, 1);
        // 不使用父类区块数组，以加载距离 1 初始化以减少数组占用。
        
        mainThread = ((IEMinecraftClient) Minecraft.getInstance()).ip_getRunningThread();
    }
    
    /** 主线程卸载指定区块，从双表移除、发NeoForge卸载事件、清level内容并通知Sodium及RemoteViewArea。 */
    @Override
    public void drop(ChunkPos chunkPos) {
        Validate.isTrue(Thread.currentThread() == mainThread);
        
// 已停用的区块卸载调试日志。
        
        LevelChunk chunk = chunkMapForMainThread.get(chunkPos.toLong());
        if (chunk != null) {
            modifyChunkMap(chunkMap -> {
                chunkMap.remove(chunkPos.toLong());
            });
            
            PlatformBridge.postClientChunkUnloadEvent(chunk);
            this.level.unload(chunk);
            SodiumInterface.invoker.onClientChunkUnloaded(level, chunkPos.x, chunkPos.z);
            RemoteViewArea.onClientChunkUnload(chunk);
        }
    }
    
    /** 主线程无锁读主表，其他线程在副表锁内调用读取函数。 */
    public <T> T readChunkMap(Function<Long2ObjectOpenHashMap<LevelChunk>, T> func) {
        if (Thread.currentThread() == mainThread) {
            return func.apply(chunkMapForMainThread);
        }
        else {
            synchronized (chunkMapForOtherThreads) {
                return func.apply(chunkMapForOtherThreads);
            }
        }
    }
    
    /** 强制主线程，先改主表再锁副表应用同一mutation。 */
    public void modifyChunkMap(Consumer<Long2ObjectOpenHashMap<LevelChunk>> func) {
        Validate.isTrue(Thread.currentThread() == mainThread);
        func.accept(chunkMapForMainThread);
        synchronized (chunkMapForOtherThreads) {
            func.accept(chunkMapForOtherThreads);
        }
    }
    
    /** 查现有区块。 */
    @Override
    public LevelChunk getChunk(int x, int z, ChunkStatus chunkStatus, boolean create) {
        return readChunkMap(chunkMap -> {
            LevelChunk chunk = chunkMap.get(ChunkPos.asLong(x, z));
            if (chunk != null) {
                return chunk;
            }
            
            return create ? this.emptyChunk : null;
        });
    }
    
    /** 已存在区块更新群系，不存在记错误。 */
    @Override
    public void replaceBiomes(int x, int z, FriendlyByteBuf friendlyByteBuf) {
        Validate.isTrue(Thread.currentThread() == mainThread);
        
        long chunkPosLong = ChunkPos.asLong(x, z);
        
        LevelChunk worldChunk = chunkMapForMainThread.get(chunkPosLong);
        if (worldChunk == null) {
            LOGGER.error("Trying to replace biomes for missing chunk {} {}", x, z);
        }
        else {
            worldChunk.replaceBiomes(friendlyByteBuf);
        }
    }
    
    /** 创建或复用LevelChunk并解码packet，加入双表，通知世界已加载、NeoForge及Sodium。 */
    @Override
    public LevelChunk replaceWithPacketData(
        int x, int z,
        FriendlyByteBuf buf, CompoundTag nbt,
        Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    ) {
        Validate.isTrue(Thread.currentThread() == mainThread);
        
        long chunkPosLong = ChunkPos.asLong(x, z);
        LevelChunk worldChunk = chunkMapForMainThread.get(chunkPosLong);
        if (worldChunk == null) {
            worldChunk = new LevelChunk(this.level, new ChunkPos(x, z));
            loadChunkDataFromPacket(buf, nbt, worldChunk, consumer);
            
            LevelChunk worldChunkToPut = worldChunk; // 匿名函数只能捕获实际未被重新赋值的变量。
            modifyChunkMap(chunkMap -> {
                chunkMap.put(chunkPosLong, worldChunkToPut);
            });
        }
        else {
            loadChunkDataFromPacket(buf, nbt, worldChunk, consumer);
        }
        
        this.level.onChunkLoaded(new ChunkPos(x, z));
        PlatformBridge.postClientChunkLoadEvent(worldChunk);
        SodiumInterface.invoker.onClientChunkLoaded(level, x, z);
        
// 已停用的区块加载调试日志。
        
        return worldChunk;
    }
    
    /** 用原版replaceWithPacketData解码。 */
    private void loadChunkDataFromPacket(
        FriendlyByteBuf buf,
        CompoundTag nbt,
        LevelChunk worldChunk,
        Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    ) {
        try {
            worldChunk.replaceWithPacketData(buf, nbt, consumer);
        }
        catch (Exception e) {
            LOGGER.error(
                "Error deserializing chunk packet {} {}",
                worldChunk.getLevel().dimension().location(),
                worldChunk.getPos(),
                e
            );
            CHelper.printChat(
                Component
                    .literal("Failed to deserialize chunk packet. %s %s %s".formatted(
                        worldChunk.getLevel().dimension().location(),
                        worldChunk.getPos().x, worldChunk.getPos().z
                    ))
                    .append(Component.literal(" Report issue:"))
                    .append(CHelper.getLinkText(PlatformBridge.getIssueLink()))
                    .withStyle(ChatFormatting.RED)
            );
            
            throw new RuntimeException(e);
        }
    }
    
    /** 空实现：目标缓存不使用原版环形窗口中心。 */
    @Override
    public void updateViewCenter(int x, int z) {
        // 此处无需额外处理。
    }
    
    /** 空实现：目标缓存不按原版固定半径裁剪。 */
    @Override
    public void updateViewRadius(int r) {
        // 此处无需额外处理。
    }
    
    /** 返回带已加载区块数量的统计字符串。 */
    @Override
    public String gatherStats() {
        return "Client Chunks (Shuttershadow) " + getLoadedChunksCount();
    }
    
    /** 通过线程安全读入口返回缓存大小。 */
    @Override
    public int getLoadedChunksCount() {
        return readChunkMap(chunkMap -> {
            return chunkMap.size();
        });
    }
    
    /** 将目标光照section标脏到该维度LevelRenderer。 */
    @Override
    public void onLightUpdate(LightLayer lightType, SectionPos chunkSectionPos) {
        ClientWorldLoader.getWorldRenderer(level.dimension())
            .setSectionDirty(chunkSectionPos.x(), chunkSectionPos.y(), chunkSectionPos.z());
    }
    
}

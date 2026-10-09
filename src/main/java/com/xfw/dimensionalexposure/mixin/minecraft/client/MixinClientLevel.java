package com.xfw.dimensionalexposure.mixin.minecraft.client;


import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.dimensionalexposure.core.ClientWorldLoader;
import com.xfw.dimensionalexposure.access.IEClientWorld;
import com.xfw.dimensionalexposure.core.chunk_loading.RemoteClientChunkMap;

import java.util.function.Supplier;

/** 管理客户端远程区块缓存、实体去重及世界状态访问。 */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements IEClientWorld {
    
    @Mutable
    @Shadow
    @Final
    private ClientChunkCache chunkSource;
    
    @Mutable
    @Shadow
    @Final
    private LevelRenderer levelRenderer;
    
    @Shadow
    @Final
    private BlockStatePredictionHandler blockStatePredictionHandler;
    
    @Shadow
    @Final
    @Mutable
    private TickRateManager tickRateManager;
    
    // 使用相机内核的客户端区块缓存。
    /** 替换客户端区块缓存，使世界能够接收相机额外订阅的区块。 */
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    void onConstructed(
        ClientPacketListener clientPacketListener, ClientLevel.ClientLevelData clientLevelData,
        ResourceKey resourceKey, Holder holder, int loadDistance, int j, Supplier supplier,
        LevelRenderer levelRenderer, boolean bl, long l, CallbackInfo ci
    ) {
        ClientLevel clientWorld = (ClientLevel) (Object) this;
        chunkSource = new RemoteClientChunkMap(clientWorld);
    }
    
    // 实体换维时避免重复保留同一实体。
    /** addEntity 完成后，在已初始化的其余客户端世界删除同数字 ID 实体，避免无缝移交后同实体留在多个维度。 */
    @Inject(
        method = "addEntity",
        at = @At("TAIL")
    )
    private void onOnEntityAdded(Entity entityIn, CallbackInfo ci) {
        if (ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
                if (world != (Object) this) {
                    world.removeEntity(entityIn.getId(), Entity.RemovalReason.DISCARDED);
                }
            }
        }
    }
    
    /** hasChunk 开头查真实 FULL 区块而不创建。 */
    @Inject(
        method = "Lnet/minecraft/client/multiplayer/ClientLevel;hasChunk(II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onHasChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Boolean> cir) {
        LevelChunk chunk = chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null || chunk instanceof EmptyLevelChunk) {
            cir.setReturnValue(false);
        }
    }
    
    /** 将 levelRenderer 引用置空，远世界完整销毁时断开其渲染器引用。 */
    @Override
    public void ip_resetWorldRendererRef() {
        levelRenderer = null;
    }
    
    /** 返回当前世界 BlockStatePredictionHandler，供预测序号/回执在所有世界同步。 */
    @Override
    public BlockStatePredictionHandler ip_getBlockStatePredictionHandler() {
        return blockStatePredictionHandler;
    }
    
    /** 替换 tickRateManager，让远世界与当前世界使用同一 tick 速度管理状态。 */
    @Override
    public void ip_setTickRateManager(TickRateManager cond) {
        tickRateManager = cond;
    }
}

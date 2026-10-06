package com.xfw.shuttershadow.mixin.minecraft.server;


import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTickets;
import com.xfw.shuttershadow.access.IEChunkMap;
import com.xfw.shuttershadow.core.CoreConfig;

/** 修复玩家移除时的空集合，并分批刷新相机加载票据。 */
@Mixin(DistanceManager.class)
public abstract class MixinDistanceManager {
    
    @Shadow
    @Final
    private Long2ObjectMap<ObjectSet<ServerPlayer>> playersPerChunk;
    
    // 避免空引用。
    /** 确保原版移除玩家时对应区块追踪集合存在，避免空引用。 */
    @Inject(method = "Lnet/minecraft/server/level/DistanceManager;removePlayer(Lnet/minecraft/core/SectionPos;Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"))
    private void onHandleChunkLeave(
        SectionPos sectionPos,
        ServerPlayer serverPlayer,
        CallbackInfo ci
    ) {
        long chunkPos = sectionPos.chunk().toLong();
        playersPerChunk.computeIfAbsent(chunkPos, k -> new ObjectOpenHashSet<>());
    }
    
    /** 分批提交相机额外区块票据，优先加载近处区块。 */
    @Inject(
        method = "runAllUpdates",
        at = @At("RETURN")
    )
    private void onRunAllUpdates(ChunkMap chunkManager, CallbackInfoReturnable<Boolean> cir) {
        if (CoreConfig.ENABLE_REMOTE_CHUNK_LOADING.get()) {
            ServerLevel world = ((IEChunkMap) chunkManager).ip_getWorld();
            RemoteChunkTickets tickets = RemoteChunkTickets.BY_DIMENSION.get(world);
            if (tickets != null) tickets.flushThrottling(world);
        }
    }
    
}

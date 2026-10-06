package com.xfw.shuttershadow.mixin.minecraft.server;
// Shuttershadow phase seven: relocated into the camera core.

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

@Mixin(DistanceManager.class)
public abstract class MixinDistanceManager {
    
    @Shadow
    @Final
    private Long2ObjectMap<ObjectSet<ServerPlayer>> playersPerChunk;
    
    // avoid NPE
    @Inject(method = "Lnet/minecraft/server/level/DistanceManager;removePlayer(Lnet/minecraft/core/SectionPos;Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"))
    private void onHandleChunkLeave(
        SectionPos sectionPos,
        ServerPlayer serverPlayer,
        CallbackInfo ci
    ) {
        long chunkPos = sectionPos.chunk().toLong();
        playersPerChunk.computeIfAbsent(chunkPos, k -> new ObjectOpenHashSet<>());
    }
    
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

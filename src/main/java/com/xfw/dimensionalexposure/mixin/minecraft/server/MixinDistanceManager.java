package com.xfw.dimensionalexposure.mixin.minecraft.server;


import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.xfw.dimensionalexposure.core.teleportation.ServerTeleportationManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.dimensionalexposure.core.chunk_loading.RemoteChunkTickets;
import com.xfw.dimensionalexposure.access.IEChunkMap;
import com.xfw.dimensionalexposure.DimensionalExposureConfig;

/** 保护本模组无缝移交的玩家票据清理，并分批刷新相机加载票据。 */
@Mixin(DistanceManager.class)
public abstract class MixinDistanceManager {

    /** 仅无缝移交缺少原集合时补空集合，继续原版清理但不写入地图。 */
    @WrapOperation(method = "removePlayer", at = @At(value = "INVOKE", target =
            "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;get(J)Ljava/lang/Object;", remap = false))
    private Object dimensionalExposure$readLeavingPlayers(Long2ObjectMap<?> players, long chunkPos,
            Operation<Object> original, @Local(argsOnly = true) ServerPlayer player) {
        Object result = original.call(players, chunkPos);
        return result == null && ServerTeleportationManager.of(player.getServer()).isTeleporting(player)
                ? new ObjectOpenHashSet<ServerPlayer>() : result;
    }
    
    /** 分批提交相机额外区块票据，优先加载近处区块。 */
    @Inject(
        method = "runAllUpdates",
        at = @At("RETURN")
    )
    private void onRunAllUpdates(ChunkMap chunkManager, CallbackInfoReturnable<Boolean> cir) {
        if (DimensionalExposureConfig.ENABLE_REMOTE_CHUNK_LOADING.get()) {
            ServerLevel world = ((IEChunkMap) chunkManager).ip_getWorld();
            RemoteChunkTickets tickets = RemoteChunkTickets.BY_DIMENSION.get(world);
            if (tickets != null) tickets.flushThrottling(world);
        }
    }
    
}

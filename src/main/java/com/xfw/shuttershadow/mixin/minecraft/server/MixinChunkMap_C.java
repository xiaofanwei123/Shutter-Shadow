package com.xfw.shuttershadow.mixin.minecraft.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.xfw.shuttershadow.access.IEChunkMap;
import com.xfw.shuttershadow.access.IETrackedEntity;
import com.xfw.shuttershadow.core.VanillaRuntimeHooks;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;
import com.xfw.shuttershadow.network.PacketRedirection;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkMap.TrackedEntity;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BooleanSupplier;

@Mixin(ChunkMap.class)
public abstract class MixinChunkMap_C implements IEChunkMap {
    @Shadow @Final private ServerLevel level;
    @Shadow protected abstract ChunkHolder getVisibleChunkIfPresent(long position);
    @Shadow @Final public Int2ObjectMap<TrackedEntity> entityMap;
    @Shadow @Final private Queue<Runnable> unloadQueue;
    @Shadow abstract void updatePlayerStatus(ServerPlayer player, boolean added);
    @Unique private int shuttershadow$shutdownUnloadBudget = -1;

    /**
     * 仅关服时限制本轮卸载次数；重排任务保留到下一轮，让生成任务释放引用后正常保存。
     * 运行中的保存或暂停不进入此分支。
     */
    @WrapMethod(method = "processUnloads")
    private void shuttershadow$yieldShutdownUnloads(BooleanSupplier hasTime, Operation<Void> original) {
        int previous = shuttershadow$shutdownUnloadBudget;
        shuttershadow$shutdownUnloadBudget = level.getServer().isRunning() ? -1 : unloadQueue.size();
        try {
            original.call(hasTime);
        } finally {
            shuttershadow$shutdownUnloadBudget = previous;
        }
    }

    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE",
            target = "Ljava/util/Queue;poll()Ljava/lang/Object;", remap = false))
    private Object shuttershadow$pollShutdownUnload(Queue<Runnable> queue, Operation<Object> original) {
        if (shuttershadow$shutdownUnloadBudget == 0) return null;
        if (shuttershadow$shutdownUnloadBudget > 0) shuttershadow$shutdownUnloadBudget--;
        return original.call(queue);
    }

    @Override
    public ServerLevel ip_getWorld() {
        return level;
    }

    @Override
    public ChunkHolder ip_getChunkHolder(long position) {
        return getVisibleChunkIfPresent(position);
    }

    @Inject(method = "applyChunkTrackingView", at = @At("RETURN"))
    private void shuttershadow$refreshExtraRange(ServerPlayer player, ChunkTrackingView view, CallbackInfo ci) {
        if (player.level() == level) RemoteChunkTracking.onNativeViewChanged(player);
    }

    /** 原版名单不变，仅为相机额外订阅补上方块、光照等更新接收者。 */
    @Inject(method = "getPlayers", at = @At("RETURN"), cancellable = true)
    private void shuttershadow$includeCameraWatchers(ChunkPos pos, boolean boundaryOnly,
            CallbackInfoReturnable<List<ServerPlayer>> cir) {
        var extra = RemoteChunkTracking.getPlayersViewingChunk(level.dimension(), pos.x, pos.z, boundaryOnly);
        if (extra.isEmpty()) return;
        var result = new ArrayList<>(cir.getReturnValue());
        for (ServerPlayer player : extra) {
            if (!result.contains(player)) result.add(player);
        }
        cir.setReturnValue(result);
    }

    /** 生物群系重发也会包含相机订阅者，发送期间明确标注所属维度。 */
    @WrapMethod(method = "resendBiomesForChunks")
    private void shuttershadow$routeBiomeUpdates(List<ChunkAccess> chunks, Operation<Void> original) {
        PacketRedirection.withForceRedirect(level, () -> original.call(chunks));
    }

    @VanillaRuntimeHooks
    @Inject(
        method = "Lnet/minecraft/server/level/ChunkMap;removeEntity(Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onUnloadEntity(Entity entity, CallbackInfo ci) {
        // when the player leave this dimension, do not stop tracking entities
        if (ServerTeleportationManager.of(entity.getServer()).isTeleporting(entity)) {
            if (entity instanceof ServerPlayer player) {
                Object tracker = entityMap.remove(entity.getId());
                if (tracker != null) {
                    ((IETrackedEntity) tracker).ip_stopTrackingExcept(null);
                }
                entityMap.values().forEach(tracked ->
                        ((IETrackedEntity) tracked).ip_onPlayerDimensionChange(player));
                // 原版负责释放 playerMap、tickets、旧视野和旧待发送区块。
                updatePlayerStatus(player, false);
            }
            else {
                entityMap.remove(entity.getId());
            }

            ci.cancel();
        }
    }

    @Override
    public void ip_onPlayerUnload(ServerPlayer oldPlayer) {
        entityMap.values().forEach(obj -> {
            obj.removePlayer(oldPlayer);
        });
    }

    @Override
    public Int2ObjectMap<TrackedEntity> ip_getEntityTrackerMap() {
        return entityMap;
    }
}

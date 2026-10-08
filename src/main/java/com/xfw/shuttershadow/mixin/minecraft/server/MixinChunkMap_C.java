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

/** 衔接原版及相机订阅，保护无缝玩家交接并限制关服卸载循环。 */
@Mixin(ChunkMap.class)
public abstract class MixinChunkMap_C implements IEChunkMap {
    @Shadow @Final private ServerLevel level;
    /** 读取已存在的可见区块持有者，不主动生成区块。 */
    @Shadow protected abstract ChunkHolder getVisibleChunkIfPresent(long position);
    @Shadow @Final public Int2ObjectMap<TrackedEntity> entityMap;
    @Shadow @Final private Queue<Runnable> unloadQueue;
    /** Shadow 引用原 updatePlayerStatus，玩家无缝从旧维度移除时仍需正确清掉原版玩家票据状态。 */
    @Shadow abstract void updatePlayerStatus(ServerPlayer player, boolean added);
    @Unique private int shuttershadow$shutdownUnloadBudget = -1;

    /** 包裹 processUnloads：服务器运行时预算为 -1，不限原流程。 */
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

    /** 包裹 processUnloads 内 Queue.poll：预算 0 返回 null 结束本轮。 */
    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE",
            target = "Ljava/util/Queue;poll()Ljava/lang/Object;", remap = false))
    private Object shuttershadow$pollShutdownUnload(Queue<Runnable> queue, Operation<Object> original) {
        if (shuttershadow$shutdownUnloadBudget == 0) return null;
        if (shuttershadow$shutdownUnloadBudget > 0) shuttershadow$shutdownUnloadBudget--;
        return original.call(queue);
    }

    /** 返回 ChunkMap.level，区块票据/广播路由取得实际世界。 */
    @Override
    public ServerLevel ip_getWorld() {
        return level;
    }

    /** 按 long 区块坐标调用原可见 holder 查找，用于服务端就绪检查，不强制加载。 */
    @Override
    public ChunkHolder ip_getChunkHolder(long position) {
        return getVisibleChunkIfPresent(position);
    }

    /** 原版追踪视野改变后重新计算相机额外订阅。 */
    @Inject(method = "applyChunkTrackingView", at = @At("RETURN"))
    private void shuttershadow$refreshExtraRange(ServerPlayer player, ChunkTrackingView view, CallbackInfo ci) {
        if (player.level() == level) RemoteChunkTracking.onNativeViewChanged(player);
    }

    /** 将相机额外观察者加入区块更新接收者，并去重。 */
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

    /** 为生物群系重发包指定所属维度，完成后恢复发送上下文。 */
    @WrapMethod(method = "resendBiomesForChunks")
    private void shuttershadow$routeBiomeUpdates(List<ChunkAccess> chunks, Operation<Void> original) {
        PacketRedirection.withForceRedirect(level, () -> original.call(chunks));
    }

    /** 接管无缝玩家移除，释放旧实体配对并保留其远景观察关系。 */
    @VanillaRuntimeHooks
    @Inject(
        method = "Lnet/minecraft/server/level/ChunkMap;removeEntity(Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onUnloadEntity(Entity entity, CallbackInfo ci) {
        // 玩家离开本维度后，按相机订阅继续跟踪远景实体。
        if (entity instanceof ServerPlayer player
                && ServerTeleportationManager.of(player.getServer()).isTeleporting(player)) {
            TrackedEntity tracker = entityMap.remove(player.getId());
            if (tracker != null) {
                ((IETrackedEntity) tracker).ip_stopTracking();
            }
            entityMap.values().forEach(tracked ->
                    ((IETrackedEntity) tracked).ip_onPlayerDimensionChange(player));
            // 原版负责释放 playerMap、tickets、旧视野和旧待发送区块。
            updatePlayerStatus(player, false);
            ci.cancel();
        }
    }

    /** 退出或重生时清理旧玩家实例的实体配对。 */
    @Override
    public void ip_onPlayerUnload(ServerPlayer oldPlayer) {
        entityMap.values().forEach(obj -> {
            obj.removePlayer(oldPlayer);
        });
    }

    /** 返回 entityMap 实际追踪器表，供跨维观察者同步，不复制表。 */
    @Override
    public Int2ObjectMap<TrackedEntity> ip_getEntityTrackerMap() {
        return entityMap;
    }
}

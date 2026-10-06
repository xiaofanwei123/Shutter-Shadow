package com.xfw.shuttershadow.mixin.minecraft.server;


import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;

import java.util.List;

/** 保持有相机订阅的服务端维度更新，并提供调试世界信息。 */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {
    
    @Shadow
    @Final
    private ServerLevelData serverLevelData;
    
    
    // 原版不会更新既无玩家也无强制加载区块的维度。
    /** 让仅被相机观察的维度继续执行服务端更新。 */
    @Redirect(
        method = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/List;isEmpty()Z"
        )
    )
    private boolean redirectIsEmpty(List list) {
        final ServerLevel this_ = (ServerLevel) (Object) this;
        if (RemoteChunkTracking.shouldLoadDimension(this_.dimension())) {
            return false;
        }
        return list.isEmpty();
    }
    
    // 用于调试。
    /** toString 开头返回 ServerWorld + 维度ID + 存档名称，日志能明确世界而非只看同名存档。 */
    @Inject(method = "Lnet/minecraft/server/level/ServerLevel;toString()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void onToString(CallbackInfoReturnable<String> cir) {
        final ServerLevel this_ = (ServerLevel) (Object) this;
        cir.setReturnValue("ServerWorld " + this_.dimension().location() +
            " " + serverLevelData.getLevelName());
    }
    
}

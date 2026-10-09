package com.xfw.dimensionalexposure.mixin.minecraft.server;


import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.xfw.dimensionalexposure.core.chunk_loading.RemoteChunkTracking;

import java.util.List;

/** 保持有相机订阅的服务端维度更新。 */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {
    
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
    
}

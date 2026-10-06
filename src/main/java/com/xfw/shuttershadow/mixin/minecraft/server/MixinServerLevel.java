package com.xfw.shuttershadow.mixin.minecraft.server;
// Shuttershadow phase seven: relocated into the camera core.

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

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {
    
    @Shadow
    @Final
    private ServerLevelData serverLevelData;
    
    
    //in vanilla if a dimension has no player and no forced chunks then it will not tick
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
    
    // for debug
    @Inject(method = "Lnet/minecraft/server/level/ServerLevel;toString()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void onToString(CallbackInfoReturnable<String> cir) {
        final ServerLevel this_ = (ServerLevel) (Object) this;
        cir.setReturnValue("ServerWorld " + this_.dimension().location() +
            " " + serverLevelData.getLevelName());
    }
    
}

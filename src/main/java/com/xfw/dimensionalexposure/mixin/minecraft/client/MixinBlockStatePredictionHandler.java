package com.xfw.dimensionalexposure.mixin.minecraft.client;


import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.dimensionalexposure.core.ClientWorldLoader;
import com.xfw.dimensionalexposure.access.IEClientWorld;

/** 在各客户端世界之间同步方块操作预测序号。 */
@Mixin(BlockStatePredictionHandler.class)
public class MixinBlockStatePredictionHandler {
    @Shadow
    private int currentSequenceNr;
    
    /** 将新产生的方块操作预测序号同步到所有客户端世界。 */
    @Inject(
        method = "startPredicting",
        at = @At("RETURN")
    )
    private void onStartPredictingEnd(CallbackInfoReturnable<BlockStatePredictionHandler> cir) {
        for (ClientLevel clientWorld : ClientWorldLoader.getClientWorlds()) {
            BlockStatePredictionHandler handler = ((IEClientWorld) clientWorld).ip_getBlockStatePredictionHandler();
            ((IEBlockStatePredictionHandler) handler).ip_setCurrentSequenceNumber(currentSequenceNr);
        }
    }
}

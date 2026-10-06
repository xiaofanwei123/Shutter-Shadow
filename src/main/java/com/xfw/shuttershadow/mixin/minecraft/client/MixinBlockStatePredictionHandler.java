package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.access.IEClientWorld;

@Mixin(BlockStatePredictionHandler.class)
public class MixinBlockStatePredictionHandler {
    @Shadow
    private int currentSequenceNr;
    
    /**
     * Each dimension has its own BlockStatePredictionHandler, because its internal map does not discriminate dimensions.
     * So all the handlers should have synchronized sequence number.
     */
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

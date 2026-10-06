package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.world.TickRateManager;

public interface IEClientWorld {
    
    void ip_resetWorldRendererRef();
    
    BlockStatePredictionHandler ip_getBlockStatePredictionHandler();
    
    void ip_setTickRateManager(TickRateManager cond);
}

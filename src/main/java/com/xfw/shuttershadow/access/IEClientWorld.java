package com.xfw.shuttershadow.access;

import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.world.TickRateManager;

/** MixinClientLevel实现的目标世界内部状态桥。 */
public interface IEClientWorld {
    
    /** 清世界对renderer的内部引用，供注销时释放。 */
    void ip_resetWorldRendererRef();
    
    /** 返回该世界的BlockStatePredictionHandler。 */
    BlockStatePredictionHandler ip_getBlockStatePredictionHandler();
    
    /** 复用真实世界TickRateManager，避免目标世界速率脱节。 */
    void ip_setTickRateManager(TickRateManager cond);
}

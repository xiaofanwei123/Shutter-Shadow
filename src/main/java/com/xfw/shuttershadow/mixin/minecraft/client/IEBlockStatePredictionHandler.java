package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 提供读取和同步各世界方块预测序号的内部访问接口。 */
@Mixin(BlockStatePredictionHandler.class)
public interface IEBlockStatePredictionHandler {
    /** Accessor 写入 currentSequenceNr。 */
    @Accessor("currentSequenceNr")
    void ip_setCurrentSequenceNumber(int arg);
}

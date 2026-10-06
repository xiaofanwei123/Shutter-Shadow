package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 客户端 ClientLevel.ClientLevelData 访问器，远世界创建时读取原世界数据是否平坦。 */
@Mixin(ClientLevel.ClientLevelData.class)
public interface IEClientLevelData {
    /** Accessor 返回 isFlat，供新客户端维度数据构造使用。 */
    @Accessor("isFlat")
    boolean ip_getIsFlat();
}

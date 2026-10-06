package com.xfw.shuttershadow.access;

import net.minecraft.server.level.ServerPlayer;

/** MixinTrackedEntity实现的相机额外实体观察者桥。 */
public interface IETrackedEntity {
    /** 重算远世界观察者并补发pairing/unpairing。 */
    void ip_updateEntityTrackingStatus();
    /** 玩家真实维度变化时修正其在此tracker中的原版/额外观察关系。 */
    void ip_onPlayerDimensionChange(ServerPlayer player);
    /** 除保留玩家以外停止所有观察关系，用于随骑乘玩家移动的载具。 */
    void ip_stopTrackingExcept(ServerPlayer preservedPlayer);
}

package com.xfw.shuttershadow.access;

import net.minecraft.server.level.ServerPlayer;

public interface IETrackedEntity {
    void ip_updateEntityTrackingStatus();
    void ip_onPlayerDimensionChange(ServerPlayer player);
    void ip_stopTrackingExcept(ServerPlayer preservedPlayer);
}

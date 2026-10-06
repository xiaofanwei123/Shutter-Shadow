package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public interface IEPlayerMoveC2SPacket {
    ResourceKey<Level> ip_getPlayerDimension();
    
    void ip_setPlayerDimension(ResourceKey<Level> dim);
}

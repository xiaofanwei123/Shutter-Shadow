package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientLevel.ClientLevelData.class)
public interface IEClientLevelData {
    @Accessor("isFlat")
    boolean ip_getIsFlat();
}

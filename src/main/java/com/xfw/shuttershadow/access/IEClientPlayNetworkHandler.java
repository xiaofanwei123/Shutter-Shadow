package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.multiplayer.ClientLevel;

public interface IEClientPlayNetworkHandler {
    void ip_setWorld(ClientLevel world);
}

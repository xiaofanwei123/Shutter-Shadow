package com.xfw.shuttershadow.event;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

public class ServerCleanupEvent extends Event {
    public final MinecraftServer server;

    public ServerCleanupEvent(MinecraftServer server) {
        this.server = server;
    }
}

package com.xfw.shuttershadow.util;

import com.xfw.shuttershadow.core.MiscGlobals;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.server.MinecraftServer;

public class MiscHelper {
    
    /**
     * TODO support multi-server-in-one-JVM
     */
    @Deprecated
    public static MinecraftServer getServer() {
        return MiscGlobals.refMinecraftServer.get();
    }
    
}

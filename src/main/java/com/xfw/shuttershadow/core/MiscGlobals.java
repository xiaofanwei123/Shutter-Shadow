package com.xfw.shuttershadow.core;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.server.MinecraftServer;

import java.lang.ref.WeakReference;

public class MiscGlobals {
    public static WeakReference<MinecraftServer> refMinecraftServer =
        new WeakReference<>(null);
    
}

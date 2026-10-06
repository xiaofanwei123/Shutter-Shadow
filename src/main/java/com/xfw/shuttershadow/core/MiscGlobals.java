package com.xfw.shuttershadow.core;


import net.minecraft.server.MinecraftServer;

import java.lang.ref.WeakReference;

/** 保存当前服务器的弱引用，供通用工具访问。 */
public class MiscGlobals {
    public static WeakReference<MinecraftServer> refMinecraftServer =
        new WeakReference<>(null);
    
}

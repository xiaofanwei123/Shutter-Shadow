package com.xfw.shuttershadow.util;

import com.xfw.shuttershadow.core.MiscGlobals;


import net.minecraft.server.MinecraftServer;

/** 当前服务端访问桥，依赖MixinMinecraftServer登记的弱引用。 */
public class MiscHelper {
    
    /**
     * TODO support multi-server-in-one-JVM
     */
    /** 取得当前服务器，尚未启动时返回空值。 */
    @Deprecated
    public static MinecraftServer getServer() {
        return MiscGlobals.refMinecraftServer.get();
    }
    
}

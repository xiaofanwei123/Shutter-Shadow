package com.xfw.shuttershadow.core;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.server.MinecraftServer;
import com.xfw.shuttershadow.access.IEMinecraftServer;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;
import com.xfw.shuttershadow.util.MyTaskList;

/** Shuttershadow 第二、三轮裁剪：移除魔杖及门户生成状态，保留世界、任务及传送状态。 */
public class ServerRuntimeState {
    public final MyTaskList taskList = new MyTaskList();
    
    
    public final ServerTeleportationManager teleportationManager = new ServerTeleportationManager();
    
    public static ServerRuntimeState of(MinecraftServer server) {
        return ((IEMinecraftServer) server).ip_getPerServerInfo();
    }
}

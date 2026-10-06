package com.xfw.shuttershadow.core;


import net.minecraft.server.MinecraftServer;
import com.xfw.shuttershadow.access.IEMinecraftServer;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;
import com.xfw.shuttershadow.util.MyTaskList;

/** 保存各服务器独立的传送管理器，避免跨存档共享状态。 */
public class ServerRuntimeState {
    public final MyTaskList taskList = new MyTaskList();
    
    
    public final ServerTeleportationManager teleportationManager = new ServerTeleportationManager();
    
    /** 通过服务器访问桥取得对应运行状态。 */
    public static ServerRuntimeState of(MinecraftServer server) {
        return ((IEMinecraftServer) server).ip_getPerServerInfo();
    }
}

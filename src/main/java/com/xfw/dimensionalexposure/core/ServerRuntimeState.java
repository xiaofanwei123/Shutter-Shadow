package com.xfw.dimensionalexposure.core;


import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import com.xfw.dimensionalexposure.access.IEMinecraftServer;
import com.xfw.dimensionalexposure.core.teleportation.ServerTeleportationManager;
import com.xfw.dimensionalexposure.util.MyTaskList;

import java.util.HashSet;
import java.util.Set;

/** 保存各服务器独立的传送和实体追踪状态，避免跨存档共享。 */
public class ServerRuntimeState {
    public final MyTaskList taskList = new MyTaskList();
    
    
    public final ServerTeleportationManager teleportationManager = new ServerTeleportationManager();

    /** 仍在观察或需要最后一次解除配对的世界。 */
    public final Set<ServerLevel> remoteEntityTrackingLevels = new HashSet<>();
    
    /** 通过服务器访问桥取得对应运行状态。 */
    public static ServerRuntimeState of(MinecraftServer server) {
        return ((IEMinecraftServer) server).ip_getPerServerInfo();
    }
}

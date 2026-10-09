package com.xfw.dimensionalexposure.core;


import com.xfw.dimensionalexposure.network.CorePayloads;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import com.xfw.dimensionalexposure.core.chunk_loading.RemoteChunkTickets;
import com.xfw.dimensionalexposure.core.chunk_loading.RemoteChunkTracking;
import com.xfw.dimensionalexposure.core.chunk_loading.WorldInfoSender;
import com.xfw.dimensionalexposure.util.ServerTaskList;
import com.xfw.dimensionalexposure.core.GcMonitor;
import com.xfw.dimensionalexposure.network.CoreNetworkHandshake;
import com.xfw.dimensionalexposure.core.teleportation.ServerTeleportationManager;

/** 通用内核引导，在业务玩法之前建立协议、远区块、世界信息和服务端传送基础。 */
public class DimensionRuntime {
    
    /** 初始化网络、远程区块、传送、内存监测及任务生命周期。 */
    public static void init(IEventBus eventBus) {
        eventBus.addListener(RegisterPayloadHandlersEvent.class, CorePayloads::register);
        CoreNetworkHandshake.init(eventBus);
        NeoForge.EVENT_BUS.addListener(CoreSettings.PostClientTickEvent.class, postClientTickEvent -> CoreSettings.CLIENT_TASK_LIST.processTasks());
        NeoForge.EVENT_BUS.addListener(CoreSettings.PreGameRenderEvent.class, preGameRenderEvent -> CoreSettings.PRE_GAME_RENDER_TASK_LIST.processTasks());
        RemoteChunkTracking.init();
        WorldInfoSender.init();
        ServerTeleportationManager.init();
        GcMonitor.initCommon();
        RemoteChunkTickets.init();
        ServerTaskList.init();
    }
    
}

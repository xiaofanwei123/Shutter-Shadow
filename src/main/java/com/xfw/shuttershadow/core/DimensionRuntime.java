package com.xfw.shuttershadow.core;
// Shuttershadow phase seven: relocated into the camera core.

import com.xfw.shuttershadow.network.CorePayloads;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTickets;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import com.xfw.shuttershadow.core.chunk_loading.WorldInfoSender;
import com.xfw.shuttershadow.util.ServerTaskList;
import com.xfw.shuttershadow.core.GcMonitor;
import com.xfw.shuttershadow.network.CoreNetworkHandshake;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;
import com.xfw.shuttershadow.util.Helper;


// Shuttershadow 第三轮裁剪：撤销传送门生成、管理命令和生成进度实体注册。
// Shuttershadow 第四轮修改：撤销空调试工具初始化，保留核心注册及生命周期顺序。
// Shuttershadow 第五轮裁剪：仅注册基础门类型，撤销跨门交互与旧门动画。
// Shuttershadow 第六轮：撤销物理门实体注册、形状、存储和碰撞初始化。
public class DimensionRuntime {
    
    public static void init(IEventBus eventBus) {
        Helper.LOGGER.info("Shutter Shadow dimension runtime initializing");

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

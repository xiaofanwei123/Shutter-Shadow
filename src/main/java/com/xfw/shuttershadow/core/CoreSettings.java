package com.xfw.shuttershadow.core;
// Shuttershadow phase seven: relocated into the camera core.

import net.neoforged.bus.api.Event;
import com.xfw.shuttershadow.util.MyTaskList;

// Shuttershadow 第三轮裁剪：移除生成系统及管理命令独占的全局配置。
// Shuttershadow 第四轮裁剪：移除无调用、无读取的旧开关。
public class CoreSettings {
    
    /**
     * It fires right after ticking client world, which is earlier than the Fabric event.
     */
    public static class PostClientTickEvent extends Event {}

    public static class PreGameRenderEvent extends Event {}
    
    // executed after ticking. will be cleared when client encounter loading screen
    public static final MyTaskList CLIENT_TASK_LIST = new MyTaskList();
    
    // won't be cleared
    public static final MyTaskList PRE_GAME_RENDER_TASK_LIST = new MyTaskList();

    
    public static volatile boolean doCheckGlError = false;
    
    public static volatile boolean activeLoading = true;
    
    public static volatile boolean saveMemoryInBufferPack = false;
    
    
    public static volatile boolean enableClientPerformanceAdjustment = true;
    
    
    
    
    public static volatile boolean chunkPacketDebug = false;
    
}

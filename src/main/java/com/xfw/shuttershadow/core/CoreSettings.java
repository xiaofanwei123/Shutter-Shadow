package com.xfw.shuttershadow.core;


import net.neoforged.bus.api.Event;
import com.xfw.shuttershadow.util.MyTaskList;

/** 保存内核运行配置及客户端任务队列。 */
public class CoreSettings {
    
    /** 客户端真实世界游戏刻结束后的内部事件。 */
    public static class PostClientTickEvent extends Event {}

    /** 每帧开始渲染前发布的内部事件。 */
    public static class PreGameRenderEvent extends Event {}
    
    // 游戏刻结束后执行，客户端进入加载界面时清空。
    public static final MyTaskList CLIENT_TASK_LIST = new MyTaskList();
    
    // 不随加载界面清空。
    public static final MyTaskList PRE_GAME_RENDER_TASK_LIST = new MyTaskList();

    
    public static volatile boolean doCheckGlError = false;
    
    public static volatile boolean activeLoading = true;
    
    public static volatile boolean saveMemoryInBufferPack = false;
    
    
    public static volatile boolean enableClientPerformanceAdjustment = true;
    
    
    
    
    
}

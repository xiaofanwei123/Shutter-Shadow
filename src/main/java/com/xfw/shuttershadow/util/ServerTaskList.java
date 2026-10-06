package com.xfw.shuttershadow.util;


import com.xfw.shuttershadow.event.ServerCleanupEvent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.xfw.shuttershadow.core.ServerRuntimeState;

/** 管理各服务器独立的任务队列，避免跨存档残留。 */
public class ServerTaskList {
    /** 注册服务端游戏刻执行和停服清理任务。 */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            of(event.getServer()).processTasks();
        });

        NeoForge.EVENT_BUS.addListener(ServerCleanupEvent.class, event -> {
            MinecraftServer server = event.server;
            of(server).forceClearTasks();
        });
    }

    // 游戏刻结束后执行，服务端关闭时清空。
    /** 取得指定服务器的任务队列。 */
    public static MyTaskList of(MinecraftServer server) {
        return ServerRuntimeState.of(server).taskList;
    }
}

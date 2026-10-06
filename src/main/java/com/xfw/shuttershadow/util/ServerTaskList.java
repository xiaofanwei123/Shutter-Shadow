package com.xfw.shuttershadow.util;
// Shuttershadow phase seven: relocated into the camera core.

import com.xfw.shuttershadow.event.ServerCleanupEvent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.xfw.shuttershadow.core.ServerRuntimeState;

public class ServerTaskList {
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            of(event.getServer()).processTasks();
        });

        NeoForge.EVENT_BUS.addListener(ServerCleanupEvent.class, event -> {
            MinecraftServer server = event.server;
            of(server).forceClearTasks();
        });
    }

    // the tasks are executed after ticking. will be cleared when server closes
    public static MyTaskList of(MinecraftServer server) {
        return ServerRuntimeState.of(server).taskList;
    }
}

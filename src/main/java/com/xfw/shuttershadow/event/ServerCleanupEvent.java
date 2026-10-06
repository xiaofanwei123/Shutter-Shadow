package com.xfw.shuttershadow.event;


import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

/** 服务端停止的内部清理事件，携带具体MinecraftServer，避免误清其他服务器实例。 */
public class ServerCleanupEvent extends Event {
    public final MinecraftServer server;

    /** 保存要清理的服务器实例。 */
    public ServerCleanupEvent(MinecraftServer server) {
        this.server = server;
    }
}

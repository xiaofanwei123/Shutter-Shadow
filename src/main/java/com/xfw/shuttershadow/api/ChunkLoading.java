package com.xfw.shuttershadow.api;

import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** 公开额外区块加载API，要求服务器线程及当前真实世界/在线玩家身份。 */
public final class ChunkLoading {
    /** 禁止实例化此工具类。 */
    private ChunkLoading() {}

    /** 校验目标后登记全局loader。 */
    public static void addGlobalChunkLoader(MinecraftServer server, ChunkLoader loader) {
        validateTarget(server, loader);
        RemoteChunkTracking.addGlobalAdditionalChunkLoader(server, loader);
    }

    /** 校验线程及loader非null后按身份移除全局loader。 */
    public static void removeGlobalChunkLoader(MinecraftServer server, ChunkLoader loader) {
        validateThread(server);
        Objects.requireNonNull(loader, "loader");
        RemoteChunkTracking.removeGlobalAdditionalChunkLoader(server, loader);
    }

    /** 校验服务器/维度/在线玩家实例后登记玩家loader。 */
    public static void addChunkLoaderForPlayer(ServerPlayer player, ChunkLoader loader) {
        Objects.requireNonNull(player, "player");
        MinecraftServer server = player.getServer();
        validateTarget(server, loader);
        if (player.isRemoved() || server.getPlayerList().getPlayer(player.getUUID()) != player) {
            throw new IllegalArgumentException("Player is not connected to this server");
        }
        RemoteChunkTracking.addPerPlayerAdditionalChunkLoader(player, loader);
    }

    /** 校验线程后按身份移除玩家loader。 */
    public static void removeChunkLoaderForPlayer(ServerPlayer player, ChunkLoader loader) {
        Objects.requireNonNull(player, "player");
        validateThread(player.getServer());
        Objects.requireNonNull(loader, "loader");
        RemoteChunkTracking.removePerPlayerAdditionalChunkLoader(player, loader);
    }

    /** 服务器非null且server.isSameThread才通过，否则抛异常。 */
    static void validateThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) throw new IllegalStateException("Chunk loading requires the server thread");
    }

    /** 复用线程校验，再检查loader及目标维度存在。 */
    private static void validateTarget(MinecraftServer server, ChunkLoader loader) {
        validateThread(server);
        Objects.requireNonNull(loader, "loader");
        if (server.getLevel(loader.dimension()) == null) {
            throw new IllegalArgumentException("Unknown dimension: " + loader.dimension().location());
        }
    }
}

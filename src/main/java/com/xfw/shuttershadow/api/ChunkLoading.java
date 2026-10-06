package com.xfw.shuttershadow.api;

import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** 额外区块加载入口；全部操作都必须在所属服务器线程执行。 */
public final class ChunkLoading {
    private ChunkLoading() {}

    /**
     * 仅在服务端保活区域，不向客户端额外同步。保存此加载对象原实例，
     * 并在使用结束、超时或取消时释放。
     */
    public static void addGlobalChunkLoader(MinecraftServer server, ChunkLoader loader) {
        validateTarget(server, loader);
        RemoteChunkTracking.addGlobalAdditionalChunkLoader(server, loader);
    }

    /**
     * 按对象身份释放；新建的同值对象不能释放原请求，重复释放无副作用。
     * 现有票据继续通过正常调度过期，不会立即强制卸载区块。
     */
    public static void removeGlobalChunkLoader(MinecraftServer server, ChunkLoader loader) {
        validateThread(server);
        Objects.requireNonNull(loader, "loader");
        RemoteChunkTracking.removeGlobalAdditionalChunkLoader(server, loader);
    }

    /**
     * 加载区域，并向此玩家同步区块及允许其看到的实体。两端都需安装
     * Shuttershadow。这里只安排更新，不保证客户端立即收到或渲染完毕。
     */
    public static void addChunkLoaderForPlayer(ServerPlayer player, ChunkLoader loader) {
        Objects.requireNonNull(player, "player");
        MinecraftServer server = player.getServer();
        validateTarget(server, loader);
        if (player.isRemoved() || server.getPlayerList().getPlayer(player.getUUID()) != player) {
            throw new IllegalArgumentException("Player is not connected to this server");
        }
        RemoteChunkTracking.addPerPlayerAdditionalChunkLoader(player, loader);
    }

    /**
     * 使用原加载对象及原玩家实例释放，玩家退出或复活后仍可清理原请求。
     * 重复释放无副作用；正常调度会移除不再被其它请求覆盖的订阅。
     */
    public static void removeChunkLoaderForPlayer(ServerPlayer player, ChunkLoader loader) {
        Objects.requireNonNull(player, "player");
        validateThread(player.getServer());
        Objects.requireNonNull(loader, "loader");
        RemoteChunkTracking.removePerPlayerAdditionalChunkLoader(player, loader);
    }

    static void validateThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) throw new IllegalStateException("Chunk loading requires the server thread");
    }

    private static void validateTarget(MinecraftServer server, ChunkLoader loader) {
        validateThread(server);
        Objects.requireNonNull(loader, "loader");
        if (server.getLevel(loader.dimension()) == null) {
            throw new IllegalArgumentException("Unknown dimension: " + loader.dimension().location());
        }
    }
}

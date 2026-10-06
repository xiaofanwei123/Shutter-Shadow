package com.xfw.shuttershadow.core;


import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.jetbrains.annotations.NotNull;
import com.xfw.shuttershadow.network.CoreNetworkHandshake;

import java.nio.file.Path;

// Shuttershadow 第三轮裁剪：移除自定义门户生成的空平台回调，保留运行时桥接。
// Shuttershadow 第五轮裁剪：保留 NeoForge 区块事件与运行环境桥，移除旧门户平台功能。
/** NeoForge平台入口集中桥接：路径、区块事件、物理端及本模组内核协议。 */
public class PlatformBridge {
    /** 取得游戏目录。 */
    public static Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    
    /** 发布客户端区块加载事件。 */
    public static void postClientChunkLoadEvent(LevelChunk chunk) {
        NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, true));
    }
    
    /** 发布区块卸载事件。 */
    public static void postClientChunkUnloadEvent(LevelChunk chunk) {
        NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
    }
    
    /** 判断当前是否运行在专用服务端。 */
    public static boolean isDedicatedServer() {
        return FMLEnvironment.dist == Dist.DEDICATED_SERVER;
    }

    /** 返回固定内核协议1.0.0。 */
    public static @NotNull CoreNetworkHandshake.ModVersion getCoreProtocolVersion() {
        return new CoreNetworkHandshake.ModVersion(
                1, 0, 0
        );
    }

    /** 取得问题反馈地址。 */
    public static String getIssueLink() {
        return "https://github.com/iPortalTeam/ImmersivePortalsMod/discussions";
    }
}

package com.xfw.shuttershadow.core;


import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** NeoForge平台入口集中桥接：区块事件、物理端与反馈地址。 */
public class PlatformBridge {
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

    /** 取得问题反馈地址。 */
    public static String getIssueLink() {
        return "https://github.com/xiaofanwei123/Shutter-Shadow/issues";
    }
}

package com.xfw.shuttershadow.core;
// Shuttershadow phase seven: relocated into the camera core.

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
public class PlatformBridge {
    public static Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    
    public static void postClientChunkLoadEvent(LevelChunk chunk) {
        NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, true));
    }
    
    public static void postClientChunkUnloadEvent(LevelChunk chunk) {
        NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
    }
    
    public static boolean isDedicatedServer() {
        return FMLEnvironment.dist == Dist.DEDICATED_SERVER;
    }

    /** Version of Shuttershadow's dimension protocol, independent of upstream releases. */
    public static @NotNull CoreNetworkHandshake.ModVersion getCoreProtocolVersion() {
        return new CoreNetworkHandshake.ModVersion(
                1, 0, 0
        );
    }

    public static String getIssueLink() {
        return "https://github.com/iPortalTeam/ImmersivePortalsMod/discussions";
    }
}

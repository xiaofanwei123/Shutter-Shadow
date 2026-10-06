package com.xfw.shuttershadow.mixin.minecraft.server;
// Shuttershadow phase seven: relocated into the camera core.

import com.xfw.shuttershadow.event.ServerCleanupEvent;
import com.mojang.datafixers.DataFixer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.core.MiscGlobals;
import com.xfw.shuttershadow.core.ServerRuntimeState;
import com.xfw.shuttershadow.access.IEMinecraftServer;

import java.lang.ref.WeakReference;
import java.net.Proxy;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer implements IEMinecraftServer {
    @Unique
    ServerRuntimeState ipPerServerInfo = new ServerRuntimeState();

    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void onConstruct(
        Thread thread, LevelStorageSource.LevelStorageAccess levelStorageAccess, PackRepository packRepository, WorldStem worldStem, Proxy proxy, DataFixer dataFixer, Services services, ChunkProgressListenerFactory chunkProgressListenerFactory, CallbackInfo ci
    ) {
        MiscGlobals.refMinecraftServer = new WeakReference<>((MinecraftServer) ((Object) this));
    }
    
    @Inject(
        method = "Lnet/minecraft/server/MinecraftServer;runServer()V",
        at = @At("RETURN")
    )
    private void onServerClose(CallbackInfo ci) {
        NeoForge.EVENT_BUS.post(new ServerCleanupEvent((MinecraftServer) (Object) this));
    }
    
    @Override
    public ServerRuntimeState ip_getPerServerInfo() {
        return ipPerServerInfo;
    }
}

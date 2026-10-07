package com.xfw.shuttershadow.mixin.minecraft.server;


import com.xfw.shuttershadow.event.ServerCleanupEvent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.core.ServerRuntimeState;
import com.xfw.shuttershadow.access.IEMinecraftServer;

/** 管理每台服务器的内核运行状态和关闭清理。 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer implements IEMinecraftServer {
    @Unique
    ServerRuntimeState ipPerServerInfo = new ServerRuntimeState();

    /** runServer 返回发 ServerCleanupEvent，清空加载、票据、传送管理等每服务器状态。 */
    @Inject(
        method = "Lnet/minecraft/server/MinecraftServer;runServer()V",
        at = @At("RETURN")
    )
    private void onServerClose(CallbackInfo ci) {
        NeoForge.EVENT_BUS.post(new ServerCleanupEvent((MinecraftServer) (Object) this));
    }
    
    /** 返回当前服务器独立的内核运行状态。 */
    @Override
    public ServerRuntimeState ip_getPerServerInfo() {
        return ipPerServerInfo;
    }
}

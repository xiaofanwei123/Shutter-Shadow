package com.xfw.dimensionalexposure.mixin.minecraft.client;


import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.dimensionalexposure.core.ClientWorldLoader;
import com.xfw.dimensionalexposure.core.CoreSettings;
import com.xfw.dimensionalexposure.access.IEClientPlayNetworkHandler;
import com.xfw.dimensionalexposure.access.IEPlayerPositionLookS2CPacket;
import com.xfw.dimensionalexposure.network.CoreNetworkHandshake;
import com.xfw.dimensionalexposure.network.PacketRedirectionClient;
import com.xfw.dimensionalexposure.core.teleportation.ClientTeleportationManager;

/** 处理多世界位置、远景骑乘关系、时钟和方块预测同步。 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener implements IEClientPlayNetworkHandler {
    @Shadow
    private ClientLevel level;
    
    /** Shadow 引用原乘客包处理方法，允许延迟任务再执行相同包。 */
    @Shadow
    public abstract void handleSetEntityPassengersPacket(ClientboundSetPassengersPacket entityPassengersSetS2CPacket_1);
    
    /** 替换监听器 level 引用，重定向包/无缝换维时使原版 handler 在正确 ClientLevel 上运行。 */
    @Override
    public void ip_setWorld(ClientLevel world) {
        this.level = world;
    }
    
    /** 位置包指定其它维度时先无缝切换世界，再由原版更新位置。 */
    @Inject(
        method = "Lnet/minecraft/client/multiplayer/ClientPacketListener;handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onProcessingPositionPacket(
        ClientboundPlayerPositionPacket packet,
        CallbackInfo ci
    ) {
        if (!CoreNetworkHandshake.doesServerHaveDimensionRuntime()) {
            return;
        }
        
        ResourceKey<Level> packetDim = ((IEPlayerPositionLookS2CPacket) packet).ip_getPlayerDimension();
        
        LocalPlayer player = Minecraft.getInstance().player;
        assert player != null;
        Level playerWorld = player.level();
        
        if (packetDim != playerWorld.dimension()) {
            
            ClientTeleportationManager.forceTeleportPlayer(
                packetDim,
                new Vec3(packet.getX(), packet.getY(), packet.getZ())
            );

// 曾在此处短暂禁用客户端传送。
        }
        
    }
    
    private boolean isReProcessingPassengerPacket;
    
    /** 载具尚未到达时，保留原世界延后重试一次，并丢弃失效连接或世界的包。 */
    @Inject(
        method = "Lnet/minecraft/client/multiplayer/ClientPacketListener;handleSetEntityPassengersPacket(Lnet/minecraft/network/protocol/game/ClientboundSetPassengersPacket;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void onOnEntityPassengersSet(
        ClientboundSetPassengersPacket entityPassengersSetS2CPacket_1,
        CallbackInfo ci
    ) {
        Entity entity_1 = this.level.getEntity(entityPassengersSetS2CPacket_1.getVehicle());
        if (entity_1 == null) {
            if (!isReProcessingPassengerPacket) {
                ClientLevel packetWorld = this.level;
                ClientPacketListener listener = (ClientPacketListener) (Object) this;
                CoreSettings.CLIENT_TASK_LIST.addTask(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null || mc.level == null || mc.getConnection() != listener) return true;
                    if (packetWorld != mc.level && (!ClientWorldLoader.getIsInitialized()
                            || !ClientWorldLoader.getClientWorlds().contains(packetWorld))) return true;
                    isReProcessingPassengerPacket = true;
                    try {
                        if (packetWorld == mc.level) {
                            handleSetEntityPassengersPacket(entityPassengersSetS2CPacket_1);
                        } else {
                            PacketRedirectionClient.handleRedirectedPacket(packetWorld.dimension(),
                                    entityPassengersSetS2CPacket_1, listener);
                        }
                    } finally {
                        isReProcessingPassengerPacket = false;
                    }
                    return true;
                });
                ci.cancel();
            }
        }
    }
    
    // 将各客户端维度的游戏时间保持同步。
    /** handleSetTime 返回后，把服务端 gameTime 写到其他已加载 ClientLevel。 */
    @Inject(
        method = "handleSetTime",
        at = @At("RETURN")
    )
    private void onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        if (ClientWorldLoader.getIsInitialized()) {
            ClientLevel currentWorld = Minecraft.getInstance().level;
            for (ClientLevel clientWorld : ClientWorldLoader.getClientWorlds()) {
                if (clientWorld != currentWorld) {
                    clientWorld.setGameTime(packet.getGameTime());
                }
            }
        }
    }
    
    /** 向所有客户端世界同步方块操作确认序号。 */
    @Redirect(
        method = "handleBlockChangedAck",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;handleBlockChangedAck(I)V"
        )
    )
    private void redirectHandleBlockChangedAck(ClientLevel instance, int seqNumber) {
        for (ClientLevel clientWorld : ClientWorldLoader.getClientWorlds()) {
            clientWorld.handleBlockChangedAck(seqNumber);
        }
    }
    
}

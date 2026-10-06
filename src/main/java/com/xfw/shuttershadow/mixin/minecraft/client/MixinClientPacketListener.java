package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.core.CoreSettings;
import com.xfw.shuttershadow.access.IEClientPlayNetworkHandler;
import com.xfw.shuttershadow.access.IEPlayerPositionLookS2CPacket;
import com.xfw.shuttershadow.network.CoreNetworkHandshake;
import com.xfw.shuttershadow.core.teleportation.ClientTeleportationManager;
import com.xfw.shuttershadow.util.Helper;
import com.xfw.shuttershadow.util.CountDownInt;

/** 处理多世界位置、载具、实体、时钟和方块预测同步。 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener implements IEClientPlayNetworkHandler {
    private static CountDownInt LOG_LIMIT = new CountDownInt(20);
    
    @Shadow
    private ClientLevel level;
    
    /** Shadow 引用原乘客包处理方法，允许延迟任务再执行相同包。 */
    @Shadow
    public abstract void handleSetEntityPassengersPacket(ClientboundSetPassengersPacket entityPassengersSetS2CPacket_1);
    
    @Shadow
    @Final
    private static Logger LOGGER;
    
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
            LOGGER.info(
                "[shuttershadow] Client accepted position packet in another dimension. Packet: {} {} {} {}. Player: {} {} {} {}",
                packetDim.location(), packet.getX(), packet.getY(), packet.getZ(),
                playerWorld.dimension().location(), player.getX(), player.getY(), player.getZ()
            );
            
            ClientTeleportationManager.forceTeleportPlayer(
                packetDim,
                new Vec3(packet.getX(), packet.getY(), packet.getZ())
            );

// 曾在此处短暂禁用客户端传送。
        }
        
        LOGGER.info(
            "[shuttershadow] Client accepted position packet {} {} {} {}",
            packetDim.location(), packet.getX(), packet.getY(), packet.getZ()
        );
    }
    
    private boolean isReProcessingPassengerPacket;
    
    /** 载具实体尚未到达时，将乘客同步包延后重试一次。 */
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
                Helper.log("Re-processed riding packet");
                CoreSettings.CLIENT_TASK_LIST.addTask(() -> {
                    isReProcessingPassengerPacket = true;
                    handleSetEntityPassengersPacket(entityPassengersSetS2CPacket_1);
                    isReProcessingPassengerPacket = false;
                    return true;
                });
                ci.cancel();
            }
        }
    }
    
    // 用于调试。
    /** 重定向 handleSetEntityData 的实体查找，只在当前处理世界取 ID。 */
    @Redirect(
        method = "Lnet/minecraft/client/multiplayer/ClientPacketListener;handleSetEntityData(Lnet/minecraft/network/protocol/game/ClientboundSetEntityDataPacket;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getEntity(I)Lnet/minecraft/world/entity/Entity;"
        )
    )
    private Entity redirectGetEntityById(ClientLevel clientWorld, int id) {
        Entity entity = clientWorld.getEntity(id);
        if (entity == null) {
            if (LOG_LIMIT.tryDecrement()) {
                LOGGER.warn("missing entity for data tracking {} {}", clientWorld, id);
            }
        }
        return entity;
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
    
    /** 保留已有乘客的实体，避免重复生成包替换无缝传送的载具。 */
    @Inject(
        method = "handleAddEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void onHandleAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        int entityId = packet.getId();
        
        Entity existingEntity = level.getEntity(entityId);
        
        if (existingEntity != null && !existingEntity.getPassengers().isEmpty()) {
            LOGGER.warn("[shuttershadow] Entity already exists and has passengers when accepting add-entity packet. Ignoring. {} {}", existingEntity, packet);
            ci.cancel();
        }
    }
    
    // 用于调试。
    /** 启用区块包调试时记录加载维度和区块坐标。 */
    @Inject(
        method = "handleLevelChunkWithLight",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onHandleLevelChunkWithLight(
        ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci
    ) {
        if (CoreSettings.chunkPacketDebug) {
            LOGGER.info("Chunk Load Packet {} {} {}", level.dimension().location(), packet.getX(), packet.getZ());
        }
    }
    
    // 用于调试。
    /** 启用区块包调试时记录卸载维度和区块坐标。 */
    @Inject(
        method = "handleForgetLevelChunk",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onHandleForgetLevelChunk(
        ClientboundForgetLevelChunkPacket packet, CallbackInfo ci
    ) {
        if (CoreSettings.chunkPacketDebug) {
            LOGGER.info(
                "Chunk Unload Packet {} {} {}",
                level.dimension().location(), packet.pos().x, packet.pos().z
            );
        }
    }
}

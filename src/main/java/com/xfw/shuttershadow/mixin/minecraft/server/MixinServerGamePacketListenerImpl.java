package com.xfw.shuttershadow.mixin.minecraft.server;


import com.xfw.shuttershadow.Shuttershadow;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.access.IEPlayerMoveC2SPacket;
import com.xfw.shuttershadow.access.IEPlayerPositionLookS2CPacket;
import com.xfw.shuttershadow.util.ServerTaskList;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;

/** 同步玩家移动和传送确认的维度，避免旧位置包污染新世界。 */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 900)
public abstract class MixinServerGamePacketListenerImpl {
    @Shadow
    public ServerPlayer player;
    @Shadow
    private Vec3 awaitingPositionFromClient;
    
    
    @Unique
    private int ip_wrongMovePacketCount = 0;
    
    /** 保存待确认传送位置所属维度，避免客户端回执混用不同维度的坐标。 */
    @SuppressWarnings("JavadocReference")
    @Unique
    private @Nullable ResourceKey<Level> ip_dimOfAwaitingPosition;
    
    // 客户端与服务端维度尚未同步时忽略移动包。
    /** 拒绝维度不同步的移动包，连续异常时重新同步玩家位置。 */
    @Inject(
        method = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"
        ),
        cancellable = true
    )
    private void onProcessMovePacket(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        ResourceKey<Level> packetDimension = ((IEPlayerMoveC2SPacket) packet).ip_getPlayerDimension();
        
        if (packetDimension == null) {
            // 原版客户端收到扩展位置包时通常已断开连接。
            // 因此正常情况下不会收到缺少维度字段的移动包。
            Shuttershadow.LOGGER.error("Player move packet is missing dimension info. Maybe the player client does not have Shuttershadow");
            ServerTaskList.of(player.server).addTask(() -> {
                player.connection.disconnect(Component.literal(
                    "The client does not have Shuttershadow"
                ));
                return true;
            });
            return;
        }
        
        if (player.level().dimension() != packetDimension) {
            ip_wrongMovePacketCount += 1;
            
            if (ip_wrongMovePacketCount > 10) {
                ServerTeleportationManager.of(player.server).forceTeleportPlayer(
                    player, player.level().dimension(), player.position()
                );
                ip_wrongMovePacketCount = 0;
            }
            
            ci.cancel();
        }
        else {
            ip_wrongMovePacketCount = 0;
        }
    }
    
    /** 阻止已移除玩家继续发送位置同步。 */
    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$guardRemovedPlayer(CallbackInfo ci) {
        if (player.getRemovalReason() != null) {
            Shuttershadow.LOGGER.error(
                "[shuttershadow] Tries to send player pos packet to a removed player {}",
                player, new Throwable()
            );
            ci.cancel();
        }
    }

    /** 原版保存待确认坐标后立即记录维度，保持位置更新前的同步时点。 */
    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At(value = "FIELD",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;awaitingPositionFromClient:Lnet/minecraft/world/phys/Vec3;",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void shuttershadow$recordAwaitingDimension(CallbackInfo ci) {
        this.ip_dimOfAwaitingPosition = player.level().dimension();
    }

    /** 为原版位置包补充维度，并沿原发送调用链处理。 */
    @WrapOperation(method = "teleport(DDDFFLjava/util/Set;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void shuttershadow$sendDimensionPosition(ServerGamePacketListenerImpl connection,
            Packet<?> packet, Operation<Void> original) {
        if (packet instanceof ClientboundPlayerPositionPacket positionPacket) {
            ((IEPlayerPositionLookS2CPacket) positionPacket).ip_setPlayerDimension(player.level().dimension());
        }
        original.call(connection, packet);
    }

    // 若待确认位置属于其它维度，则将玩家同步到该维度。
    // 避免不同维度的坐标互相覆盖。
    /** 传送回执指向其它维度时，将玩家同步到已确认的位置。 */
    @Inject(
        method = "handleAcceptTeleportPacket",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;absMoveTo(DDDFF)V"
        )
    )
    private void onHandleAcceptTeleportPacket(
        ServerboundAcceptTeleportationPacket packet, CallbackInfo ci
    ) {
        if (ip_dimOfAwaitingPosition == null) {
            Shuttershadow.LOGGER.error("[shuttershadow] ip_dimOfAwaitingPosition is null {}", player);
            return;
        }
        
        if (ip_dimOfAwaitingPosition != player.level().dimension()) {
            
            ServerLevel destWorld = player.server.getLevel(ip_dimOfAwaitingPosition);
            
            if (destWorld == null) {
                Shuttershadow.LOGGER.error(
                    "[shuttershadow] Cannot find destination world {}",
                    ip_dimOfAwaitingPosition.location()
                );
                return;
            }
            
            ServerTeleportationManager.of(player.server)
                .forceTeleportPlayer(
                    player, ip_dimOfAwaitingPosition,
                    awaitingPositionFromClient, false
                );
            ip_dimOfAwaitingPosition = null;
        }
    }
    
    /** 原版纠偏位置改变时同步保存其所属维度。 */
    @Inject(
        method = "handlePlayerCommand",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;awaitingPositionFromClient:Lnet/minecraft/world/phys/Vec3;",
            opcode = Opcodes.PUTFIELD
        )
    )
    private void onTeleportPlayerCancelSleeping(
        ServerboundPlayerCommandPacket packet, CallbackInfo ci
    ) {
        ip_dimOfAwaitingPosition = player.level().dimension();
    }
    

}

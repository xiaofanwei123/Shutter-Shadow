package com.xfw.shuttershadow.mixin.minecraft.server;


import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.access.IEPlayerMoveC2SPacket;
import com.xfw.shuttershadow.access.IEPlayerPositionLookS2CPacket;
import com.xfw.shuttershadow.util.ServerTaskList;
import com.xfw.shuttershadow.core.VanillaRuntimeHooks;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;

import java.util.Set;

/** 同步玩家移动和传送确认的维度，避免旧位置包污染新世界。 */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 900)
public abstract class MixinServerGamePacketListenerImpl {
    @Shadow
    public ServerPlayer player;
    @Shadow
    private Vec3 awaitingPositionFromClient;
    @Shadow
    private int awaitingTeleport;
    @Shadow
    private int awaitingTeleportTime;
    @Shadow
    private int tickCount;

    /** Shadow 引用原 getPlayer，保留原连接玩家查询声明。 */
    @Shadow
    public abstract ServerPlayer getPlayer();
    
    
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
    
    /**
     * 更新待确认位置及维度，并发送扩展位置同步包。
     * @author qouteall
     * @reason 为原版位置同步包附加维度信息及传送确认处理。
     */
    @Overwrite
    @VanillaRuntimeHooks
    public void teleport(
        double x, double y, double z, float yaw, float pitch,
        Set<RelativeMovement> relativeAttrs
    ) {
        // 重生期间玩家可能已标记移除，仍有传送请求到达。
        
        if (player.getRemovalReason() != null) {
            Shuttershadow.LOGGER.error(
                "[shuttershadow] Tries to send player pos packet to a removed player {}",
                player, new Throwable()
            );
            return;
        }
        
        
        double xBase = relativeAttrs.contains(RelativeMovement.X) ? this.player.getX() : 0.0;
        double yBase = relativeAttrs.contains(RelativeMovement.Y) ? this.player.getY() : 0.0;
        double zBase = relativeAttrs.contains(RelativeMovement.Z) ? this.player.getZ() : 0.0;
        float yRotBase = relativeAttrs.contains(RelativeMovement.Y_ROT) ? this.player.getYRot() : 0.0f;
        float xRotBase = relativeAttrs.contains(RelativeMovement.X_ROT) ? this.player.getXRot() : 0.0f;
        
        this.awaitingPositionFromClient = new Vec3(x, y, z);
        this.ip_dimOfAwaitingPosition = player.level().dimension();
        if (++this.awaitingTeleport == Integer.MAX_VALUE) {
            this.awaitingTeleport = 0;
        }
        
        this.awaitingTeleportTime = this.tickCount;
        this.player.absMoveTo(x, y, z, yaw, pitch);
        ClientboundPlayerPositionPacket lookPacket = new ClientboundPlayerPositionPacket(
            x - xBase, y - yBase, z - zBase,
            yaw - yRotBase, pitch - xRotBase,
            relativeAttrs, this.awaitingTeleport
        );
        
        ((IEPlayerPositionLookS2CPacket) lookPacket).ip_setPlayerDimension(player.level().dimension());
        
        this.player.connection.send(lookPacket);
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

package com.xfw.shuttershadow.mixin.minecraft.server;
// Shuttershadow phase seven: relocated into the camera core.

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
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
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
import com.xfw.shuttershadow.core.CoreConfig;
import com.xfw.shuttershadow.core.teleportation.ServerTeleportationManager;
import com.xfw.shuttershadow.util.CountDownInt;

import java.util.Set;

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

    @Shadow
    @Final
    static Logger LOGGER;
    
    @Shadow
    public abstract ServerPlayer getPlayer();
    
    
    @Unique
    private static final CountDownInt LOG_LIMIT = new CountDownInt(20);
    
    @Unique
    private int ip_wrongMovePacketCount = 0;
    
    /**
     * Attach the dimension information to
     * {@link ServerGamePacketListenerImpl#awaitingPositionFromClient}
     *
     * The teleport system: when server wants to teleport a player,
     * the server will send the {@link ClientboundPlayerPositionPacket}, then
     * set {@link ServerGamePacketListenerImpl#awaitingPositionFromClient}
     * and {@link ServerGamePacketListenerImpl#awaitingTeleport} counter.
     *
     * Before the client sending {@link ServerboundAcceptTeleportationPacket},
     * the position packets are ignored, and some of the item using packets are ignored.
     *
     * Attach the dimension information to the position, to avoid messing up coordinates
     * of different dimensions.
     */
    @SuppressWarnings("JavadocReference")
    @Unique
    private @Nullable ResourceKey<Level> ip_dimOfAwaitingPosition;
    
    //do not process move packet when client dimension and server dimension are not synced
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
            // this actually never happens, because the vanilla client will disconnect immediately
            // when receiving the position sync packet that has the extra dimension field
            LOGGER.error("Player move packet is missing dimension info. Maybe the player client does not have Shuttershadow");
            ServerTaskList.of(player.server).addTask(() -> {
                player.connection.disconnect(Component.literal(
                    "The client does not have Shuttershadow"
                ));
                return true;
            });
            return;
        }
        
        if (player.level().dimension() != packetDimension) {
            if (LOG_LIMIT.tryDecrement()) {
                LOGGER.info(
                    "[shuttershadow] Ignoring player move packet. Player: {} Packet: {} {} {} {}",
                    player, packetDimension.location(),
                    packet.getX(player.getX()),
                    packet.getY(player.getY()),
                    packet.getZ(player.getZ())
                );
            }
            
            ip_wrongMovePacketCount += 1;
            
            if (ip_wrongMovePacketCount > 10) {
                LOGGER.info(
                    "[shuttershadow] Force move player {} {} {}",
                    player, player.level().dimension().location(), player.position()
                );
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
     * @reason make PlayerPositionLookS2CPacket contain dimension data and do some special handling
     * @author qouteall
     */
    @Overwrite
    @VanillaRuntimeHooks
    public void teleport(
        double x, double y, double z, float yaw, float pitch,
        Set<RelativeMovement> relativeAttrs
    ) {
        // it may request teleport while this.player is marked removed during respawn
        
        if (player.getRemovalReason() != null) {
            LOGGER.error(
                "[shuttershadow] Tries to send player pos packet to a removed player {}",
                player, new Throwable()
            );
            return;
        }
        
        if (CoreConfig.SERVER_TELEPORT_LOGGING.get()) {
            LOGGER.info(
                "Teleporting player {} to {} {} {} {}",
                player, player.level().dimension().location(), x, y, z
            );
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

    // if the awaiting position is in a different dimension, move the player accordingly
    // avoid messing up position for different dimensions
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
            LOGGER.error("[shuttershadow] ip_dimOfAwaitingPosition is null {}", player);
            return;
        }
        
        if (ip_dimOfAwaitingPosition != player.level().dimension()) {
            LOGGER.info(
                "Accepted teleport to another dimension {} {}",
                ip_dimOfAwaitingPosition, awaitingPositionFromClient
            );
            
            ServerLevel destWorld = player.server.getLevel(ip_dimOfAwaitingPosition);
            
            if (destWorld == null) {
                LOGGER.error(
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

package com.xfw.shuttershadow.mixin.minecraft.server;
// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.authlib.GameProfile;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import com.xfw.shuttershadow.access.IEServerPlayerEntity;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;

@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer extends Player implements IEServerPlayerEntity {
    @Shadow
    private Vec3 enteredNetherPosition;
    
    public MixinServerPlayer(Level level, BlockPos blockPos, float f, GameProfile gameProfile) {
        super(level, blockPos, f, gameProfile);
    }
    
    @Shadow protected abstract void triggerDimensionChangeTriggers(ServerLevel origin);

    /** NeoForge 允许取消换维；取消时保留当前相机订阅，通过后再清理一次。 */
    @WrapOperation(method = "changeDimension", at = @At(value = "INVOKE", target =
            "Lnet/neoforged/neoforge/common/CommonHooks;onTravelToDimension(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceKey;)Z",
            remap = false))
    private boolean shuttershadow$clearCameraTrackingBeforeVanillaTransfer(
            Entity entity, ResourceKey<Level> dimension, Operation<Boolean> original) {
        boolean allowed = original.call(entity, dimension);
        if (allowed) {
            RemoteChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers((ServerPlayer) entity);
        }
        return allowed;
    }
    
    @Override
    public void ip_stopRidingWithoutTeleportRequest() {
        super.stopRiding();
    }
    
    @Override
    public void ip_startRidingWithoutTeleportRequest(Entity newVehicle) {
        super.startRiding(newVehicle, true);
    }
    
    /**
     * See {@link ServerPlayer#changeDimension(DimensionTransition)}
     */
    @Override
    public void portal_worldChanged(ServerLevel fromWorld, Vec3 fromPos) {
        if (fromWorld.dimension() == Level.OVERWORLD && this.level().dimension() == Level.NETHER) {
            enteredNetherPosition = fromPos;
        }
        triggerDimensionChangeTriggers(fromWorld);
    }
}

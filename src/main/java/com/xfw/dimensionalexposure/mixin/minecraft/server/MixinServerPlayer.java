package com.xfw.dimensionalexposure.mixin.minecraft.server;


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
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import com.xfw.dimensionalexposure.access.IEServerPlayerEntity;
import com.xfw.dimensionalexposure.core.chunk_loading.RemoteChunkTracking;

/** 提供玩家换维进度桥，并清理获准换维的相机订阅。 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer extends Player implements IEServerPlayerEntity {
    @Shadow
    private Vec3 enteredNetherPosition;
    
    /** Mixin 继承 Player 所需构造器，原样转发 level/出生坐标/旋转/profile。 */
    public MixinServerPlayer(Level level, BlockPos blockPos, float f, GameProfile gameProfile) {
        super(level, blockPos, f, gameProfile);
    }
    
    /** 调用原版玩家换维进度和维度触发逻辑。 */
    @Shadow protected abstract void triggerDimensionChangeTriggers(ServerLevel origin);

    /** 原版换维事件通过后清理相机订阅，取消时保留。 */
    @WrapOperation(method = "changeDimension", at = @At(value = "INVOKE", target =
            "Lnet/neoforged/neoforge/common/CommonHooks;onTravelToDimension(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceKey;)Z",
            remap = false))
    private boolean dimensionalExposure$clearCameraTrackingBeforeVanillaTransfer(
            Entity entity, ResourceKey<Level> dimension, Operation<Boolean> original) {
        boolean allowed = original.call(entity, dimension);
        if (allowed) {
            RemoteChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers((ServerPlayer) entity);
        }
        return allowed;
    }
    
    /** 换维后记录下界入口位置，并触发原版换维进度。 */
    @Override
    public void portal_worldChanged(ServerLevel fromWorld, Vec3 fromPos) {
        if (fromWorld.dimension() == Level.OVERWORLD && this.level().dimension() == Level.NETHER) {
            enteredNetherPosition = fromPos;
        }
        triggerDimensionChangeTriggers(fromWorld);
    }
}

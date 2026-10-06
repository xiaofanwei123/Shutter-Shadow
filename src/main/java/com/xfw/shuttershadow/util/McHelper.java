package com.xfw.shuttershadow.util;

import com.xfw.shuttershadow.access.IEChunkMap;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import com.xfw.shuttershadow.core.VanillaRuntimeHooks;

// mc related helper methods
// Shuttershadow 第四轮裁剪：移除仅供旧生成、管理命令和 NBT 文本格式化使用的五项孤立工具。
public class McHelper {
    
    public static ResourceLocation newResourceLocation(String a) {
        return ResourceLocation.parse(a);
    }
    
    public static Vec3 lastTickPosOf(Entity entity) {
        return new Vec3(entity.xo, entity.yo, entity.zo);
    }
    
    @Deprecated
    public static ServerLevel getOverWorldOnServer() {
        return MiscHelper.getServer().getLevel(Level.OVERWORLD);
    }
    
    public static long getServerGameTime() {
        return getOverWorldOnServer().getGameTime();
    }
    
    public static int getLoadDistanceOnServer(MinecraftServer server) {
        return server.getPlayerList().getViewDistance();
    }
    
    /**
     * {@link ChunkMap#getPlayerViewDistance(ServerPlayer)}
     */
    @SuppressWarnings("JavadocReference")
    @VanillaRuntimeHooks
    public static int getPlayerLoadDistance(ServerPlayer player) {
        assert player.getServer() != null;
        int loadDistanceOnServer = getLoadDistanceOnServer(player.getServer());
        return Mth.clamp(player.requestedViewDistance(), 2, loadDistanceOnServer);
    }
    
    public static void setPosAndLastTickPos(
        Entity entity,
        Vec3 pos,
        Vec3 lastTickPos
    ) {
        entity.setPosRaw(pos.x, pos.y, pos.z);
        entity.xOld = lastTickPos.x;
        entity.yOld = lastTickPos.y;
        entity.zOld = lastTickPos.z;
        entity.xo = lastTickPos.x;
        entity.yo = lastTickPos.y;
        entity.zo = lastTickPos.z;
    }
    
    public static void setEyePos(Entity entity, Vec3 eyePos, Vec3 lastTickEyePos) {
        Vec3 eyeOffset = getEyeOffset(entity);
        
        setPosAndLastTickPos(
            entity,
            eyePos.subtract(eyeOffset),
            lastTickEyePos.subtract(eyeOffset)
        );
    }
    
    /**
     * {@link Entity#positionRider(Entity)}
     * TODO fix for non-default gravity
     */
    public static Vec3 getVehicleOffsetFromPassenger(Entity vehicle, Entity passenger) {
        Vec3 vehicleAttachmentPoint = passenger.getVehicleAttachmentPoint(vehicle);

        return vehicleAttachmentPoint;
    }
    
    public static void adjustVehicle(Entity entity) {
        Entity vehicle = entity.getVehicle();
        if (vehicle == null) {
            return;
        }
        
        Vec3 vehicleOffset = getVehicleOffsetFromPassenger(vehicle, entity);
        
        Vec3 currVelocity = vehicle.getDeltaMovement();
        
        Vec3 newVehiclePos = entity.position().add(vehicleOffset);
        Vec3 newVehicleLastTickPos = McHelper.lastTickPosOf(entity).add(vehicleOffset);
        
        // minecarts, boats and LivingEntity use position interpolation
        // don't make interpolate, or it may interpolate into unloaded chunks
        vehicle.setPos(newVehiclePos.x(), newVehiclePos.y(), newVehiclePos.z());
        vehicle.lerpTo(
            newVehiclePos.x(), newVehiclePos.y(), newVehiclePos.z(),
            vehicle.getYRot(), vehicle.getXRot(), 0
        );
        
        McHelper.setPosAndLastTickPos(
            vehicle, newVehiclePos, newVehicleLastTickPos
        );
        
        vehicle.setDeltaMovement(currVelocity);
        
    }
    
    public static LevelChunk getServerChunkIfPresent(
        ServerLevel world, int x, int z
    ) {
        ChunkHolder chunkHolder_ = ((IEChunkMap) world.getChunkSource().chunkMap).ip_getChunkHolder(ChunkPos.asLong(x, z));
        if (chunkHolder_ == null) {
            return null;
        }
        return chunkHolder_.getTickingChunk();
    }
    
    public static void updateBoundingBox(Entity player) {
        player.setPos(player.getX(), player.getY(), player.getZ());
    }
    
    

    

    
    public static MutableComponent getLinkText(String link) {
        return Component.literal(link).withStyle(
            style -> style.withClickEvent(new ClickEvent(
                ClickEvent.Action.OPEN_URL, link
            )).withUnderlined(true)
        );
    }
    
    public static void validateOnServerThread() {
        Validate.isTrue(Thread.currentThread() == MiscHelper.getServer().getRunningThread(), "must be on server thread");
    }
    
    
    public static boolean isServerChunkFullyLoaded(ServerLevel world, ChunkPos chunkPos) {
        LevelChunk chunk = getServerChunkIfPresent(
            world, chunkPos.x, chunkPos.z
        );
        
        if (chunk == null) {
            return false;
        }
        
        boolean entitiesLoaded = world.areEntitiesLoaded(chunkPos.toLong());
        
        return entitiesLoaded;
    }
    
    public static @NotNull ServerLevel getServerWorld(
        MinecraftServer server, ResourceKey<Level> dim
    ) {
        ServerLevel world = server.getLevel(dim);
        if (world == null) {
            throw new RuntimeException("Missing dimension " + dim.location());
        }
        return world;
    }
    

    
    public static int getMinY(LevelAccessor world) {
        return world.getMinBuildHeight();
    }
    
    public static int getMinSectionY(LevelAccessor world) {
        return world.getMinSection();
    }
    
    public static int getMaxSectionYExclusive(LevelAccessor world) {
        return world.getMaxSection();
    }
    
    public static int getYSectionNumber(LevelAccessor world) {
        return getMaxSectionYExclusive(world) - getMinSectionY(world);
    }
    
    public static Vec3 getEyeOffset(Entity entity) {
        return new Vec3(0, entity.getEyeHeight(), 0);
    }
}

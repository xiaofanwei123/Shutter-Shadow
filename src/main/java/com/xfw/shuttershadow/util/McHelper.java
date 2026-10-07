package com.xfw.shuttershadow.util;

import com.xfw.shuttershadow.access.IEChunkMap;


import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import com.xfw.shuttershadow.core.VanillaRuntimeHooks;

// 游戏世界与实体相关的辅助方法。
/** 跨维度实体位置/眼高/载具与现有区块状态工具。 */
public class McHelper {
    
    /** 取得实体上一游戏刻的脚底位置。 */
    public static Vec3 lastTickPosOf(Entity entity) {
        return new Vec3(entity.xo, entity.yo, entity.zo);
    }
    
    /** 将玩家视距限制在服务器允许的范围内。 */
    @VanillaRuntimeHooks
    public static int getPlayerLoadDistance(ServerPlayer player) {
        assert player.getServer() != null;
        int loadDistanceOnServer = player.getServer().getPlayerList().getViewDistance();
        return Mth.clamp(player.requestedViewDistance(), 2, loadDistanceOnServer);
    }
    
    /** 同时更新实体当前位置和历史位置，保持插值一致。 */
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
    
    /** 从眼位减实体眼高，再统一设置当前和历史脚底位置。 */
    public static void setEyePos(Entity entity, Vec3 eyePos, Vec3 lastTickEyePos) {
        Vec3 eyeOffset = getEyeOffset(entity);
        
        setPosAndLastTickPos(
            entity,
            eyePos.subtract(eyeOffset),
            lastTickEyePos.subtract(eyeOffset)
        );
    }
    
    /** 保留载具速度，将载具及其插值位置对齐到乘客。 */
    public static void adjustVehicle(Entity entity) {
        Entity vehicle = entity.getVehicle();
        if (vehicle == null) {
            return;
        }
        
        Vec3 vehicleOffset = entity.getVehicleAttachmentPoint(vehicle);
        
        Vec3 currVelocity = vehicle.getDeltaMovement();
        
        Vec3 newVehiclePos = entity.position().add(vehicleOffset);
        Vec3 newVehicleLastTickPos = McHelper.lastTickPosOf(entity).add(vehicleOffset);
        
        // 矿车、船和生物实体使用位置插值，
        // 此处直接更新位置，避免插值经过未加载区块。
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
    
    /** 取得已可运行的现有区块，不触发区块创建。 */
    public static LevelChunk getServerChunkIfPresent(
        ServerLevel world, int x, int z
    ) {
        ChunkHolder chunkHolder_ = ((IEChunkMap) world.getChunkSource().chunkMap).ip_getChunkHolder(ChunkPos.asLong(x, z));
        if (chunkHolder_ == null) {
            return null;
        }
        return chunkHolder_.getTickingChunk();
    }
    
    /** 根据当前位置重新计算实体包围盒。 */
    public static void updateBoundingBox(Entity player) {
        player.setPos(player.getX(), player.getY(), player.getZ());
    }
    /** 判断区块及其实体是否已完全加载。 */
    public static boolean isServerChunkFullyLoaded(ServerLevel world, ChunkPos chunkPos) {
        LevelChunk chunk = getServerChunkIfPresent(
            world, chunkPos.x, chunkPos.z
        );
        
        if (chunk == null) {
            return false;
        }
        
        return world.areEntitiesLoaded(chunkPos.toLong());
    }
    
    /** 返回(0, eyeHeight, 0)眼位偏移。 */
    public static Vec3 getEyeOffset(Entity entity) {
        return new Vec3(0, entity.getEyeHeight(), 0);
    }
}

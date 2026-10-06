package com.xfw.shuttershadow.util;

import com.xfw.shuttershadow.access.IEChunkMap;


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

// 游戏世界与实体相关的辅助方法。
// Shuttershadow 第四轮裁剪：移除仅供旧生成、管理命令和 NBT 文本格式化使用的五项孤立工具。
/** 跨维度实体位置/眼高/载具与现有区块状态工具。 */
public class McHelper {
    
    /** 将字符串解析为资源标识。 */
    public static ResourceLocation newResourceLocation(String a) {
        return ResourceLocation.parse(a);
    }
    
    /** 取得实体上一游戏刻的脚底位置。 */
    public static Vec3 lastTickPosOf(Entity entity) {
        return new Vec3(entity.xo, entity.yo, entity.zo);
    }
    
    /** 从当前服务端弱引用取主世界。 */
    @Deprecated
    public static ServerLevel getOverWorldOnServer() {
        return MiscHelper.getServer().getLevel(Level.OVERWORLD);
    }
    
    /** 取得服务端主世界的游戏时间。 */
    public static long getServerGameTime() {
        return getOverWorldOnServer().getGameTime();
    }
    
    /** 取得服务器配置的区块视距。 */
    public static int getLoadDistanceOnServer(MinecraftServer server) {
        return server.getPlayerList().getViewDistance();
    }
    
    /** 将玩家视距限制在服务器允许的范围内。 */
    @SuppressWarnings("JavadocReference")
    @VanillaRuntimeHooks
    public static int getPlayerLoadDistance(ServerPlayer player) {
        assert player.getServer() != null;
        int loadDistanceOnServer = getLoadDistanceOnServer(player.getServer());
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
    
    /**
     * {@link Entity#positionRider(Entity)}
     * TODO fix for non-default gravity
     */
    /** 取得乘客相对载具的骑乘附着偏移。 */
    public static Vec3 getVehicleOffsetFromPassenger(Entity vehicle, Entity passenger) {
        Vec3 vehicleAttachmentPoint = passenger.getVehicleAttachmentPoint(vehicle);

        return vehicleAttachmentPoint;
    }
    
    /** 保留载具速度，将载具及其插值位置对齐到乘客。 */
    public static void adjustVehicle(Entity entity) {
        Entity vehicle = entity.getVehicle();
        if (vehicle == null) {
            return;
        }
        
        Vec3 vehicleOffset = getVehicleOffsetFromPassenger(vehicle, entity);
        
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
    
    

    

    
    /** 创建带下划线的可点击链接文本。 */
    public static MutableComponent getLinkText(String link) {
        return Component.literal(link).withStyle(
            style -> style.withClickEvent(new ClickEvent(
                ClickEvent.Action.OPEN_URL, link
            )).withUnderlined(true)
        );
    }
    
    /** 检查当前调用是否在服务端主线程。 */
    public static void validateOnServerThread() {
        Validate.isTrue(Thread.currentThread() == MiscHelper.getServer().getRunningThread(), "must be on server thread");
    }
    
    
    /** 判断区块及其实体是否已完全加载。 */
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
    
    /** 取得指定服务端世界，维度不存在时抛出异常。 */
    public static @NotNull ServerLevel getServerWorld(
        MinecraftServer server, ResourceKey<Level> dim
    ) {
        ServerLevel world = server.getLevel(dim);
        if (world == null) {
            throw new RuntimeException("Missing dimension " + dim.location());
        }
        return world;
    }
    

    
    /** 读世界最小方块Y。 */
    public static int getMinY(LevelAccessor world) {
        return world.getMinBuildHeight();
    }
    
    /** 取得世界最低区段的纵向坐标。 */
    public static int getMinSectionY(LevelAccessor world) {
        return world.getMinSection();
    }
    
    /** 取得世界最高区段的纵向边界，不包含边界值。 */
    public static int getMaxSectionYExclusive(LevelAccessor world) {
        return world.getMaxSection();
    }
    
    /** 计算世界的纵向区段数量。 */
    public static int getYSectionNumber(LevelAccessor world) {
        return getMaxSectionYExclusive(world) - getMinSectionY(world);
    }
    
    /** 返回(0, eyeHeight, 0)眼位偏移。 */
    public static Vec3 getEyeOffset(Entity entity) {
        return new Vec3(0, entity.getEyeHeight(), 0);
    }
}

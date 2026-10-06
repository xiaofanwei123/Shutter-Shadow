package com.xfw.shuttershadow;

import com.xfw.shuttershadow.network.RemoteCameraSession;
import com.xfw.shuttershadow.network.RemoteStandPreparation;
import com.xfw.shuttershadow.api.DimensionFilters;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import io.github.mortuusars.exposure.world.item.camera.Attachment;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.List;
import java.util.Comparator;

/** 把真实来源CameraHolder包装为目标世界中的未注册Observation实体，供Exposure计算视角、照片元数据及目标实体。 */
public final class RemoteCaptureContext implements CameraHolder {
    private final CameraHolder source;
    private final Entity observation;
    private final ServerLevel remoteLevel;
    private final double coordinateScale;
    private final Vec3 sourceOrigin;
    private final Vec3 targetOrigin;
    private final int cameraStandId;
    private List<ServerPlayer> capturedPlayers;

    /** 从有效预览会话取远世界、原点、比例和支架ID，委托完整构造器。 */
    private RemoteCaptureContext(CameraHolder source, RemoteCameraSession session,
                                 ServerPlayer player, Entity cameraEntity) {
        this(source, cameraEntity, session.remoteLevel(), session.coordinateScale(),
                session.sourceOrigin(), session.targetCameraPosition(player), session.cameraStandId());
    }

    /** 保存坐标映射与来源Holder，创建不加入世界实体列表的目标观察实体。 */
    private RemoteCaptureContext(CameraHolder source, Entity cameraEntity,
                                 ServerLevel remoteLevel, double coordinateScale,
                                 Vec3 sourceOrigin, Vec3 targetOrigin, int cameraStandId) {
        this.source = source;
        this.remoteLevel = remoteLevel;
        this.coordinateScale = coordinateScale;
        this.sourceOrigin = sourceOrigin;
        this.targetOrigin = targetOrigin;
        this.cameraStandId = cameraStandId;
        this.observation = new Observation(remoteLevel, cameraEntity, targetOrigin);
    }

    /** 红石支架返回null以保持源维度照片。 */
    public static RemoteCaptureContext resolve(CameraHolder source, ItemStack camera) {
        // 红石照片只查询相机所在维度；目标上下文仅由传送入口单独取得。
        if (source.asHolderEntity() instanceof CameraStandEntity stand
                && RemoteStandPreparation.isRedstoneCapture(stand)) return null;
        RemoteCaptureContext prepared = RemoteStandPreparation.preparedContext(source, camera);
        return prepared != null ? prepared : resolveRoute(source, camera);
    }

    /** 为纯传送读取路由，不受红石照片必须留在源维度的限制。 */
    public static RemoteCaptureContext resolveForTransfer(CameraHolder source, ItemStack camera) {
        return resolveRoute(source, camera);
    }

    /** 确认当前摄影师身份和相机类型，验证当前滤镜路由与会话一致。 */
    private static RemoteCaptureContext resolveRoute(CameraHolder source, ItemStack camera) {
        ServerPlayer player = resolveExecutingPlayer(source);
        if (player == null) return null;
        RemoteCameraSession session = RemoteCameraSession.active(player.getUUID());
        Entity cameraEntity = source.asHolderEntity();
        if (session != null) {
            boolean matchesSession = session.remoteLevel() != null
                    && ((session.cameraStandId() >= 0
                    && cameraEntity instanceof CameraStandEntity stand
                    && stand.getId() == session.cameraStandId())
                    || (session.cameraStandId() < 0 && cameraEntity == player));
            if (!matchesSession) session = null;
        }
        ItemStack filter = Attachment.FILTER.get(camera).getForReading();
        if (filter.isEmpty()) return null;
        ServerLevel sourceLevel = (ServerLevel) cameraEntity.level();
        DimensionFilters.Route mapping = DimensionFilters.resolve(
                sourceLevel.registryAccess(), filter, sourceLevel.dimension().location());
        if (mapping == null || mapping.dimension() == null) return null;
        if (session != null && !session.matchesRoute(mapping)) session = null;
        if (session != null) {
            return new RemoteCaptureContext(source, session, player, cameraEntity);
        }

        // 独立解析支架滤镜路由，手动远景拍摄与红石传送都可查询同一个目标。
        if (!(cameraEntity instanceof CameraStandEntity stand)) return null;
        ResourceKey<Level> targetKey = ResourceKey.create(Registries.DIMENSION, mapping.dimension());
        if (targetKey.equals(sourceLevel.dimension())) return null;
        ServerLevel remoteLevel = player.getServer().getLevel(targetKey);
        if (remoteLevel == null) return null;
        double scale = DimensionFilters.horizontalScale(mapping, sourceLevel, remoteLevel);
        Vec3 sourceOrigin = stand.position();
        Vec3 targetOrigin = DimensionFilters.mapAbsolute(sourceOrigin, scale);
        return new RemoteCaptureContext(source, cameraEntity, remoteLevel, scale,
                sourceOrigin, targetOrigin, stand.getId());
    }

    /** 返回真实来源CameraHolder。 */
    public CameraHolder source() { return source; }
    /** 返回目标ServerLevel。 */
    public ServerLevel level() { return remoteLevel; }
    /** 返回支架实体ID，手持为负值。 */
    public int cameraStandId() { return cameraStandId; }
    /** 返回水平坐标映射比例。 */
    public double coordinateScale() { return coordinateScale; }

    /** 有冻结名单时返回它。 */
    public List<ServerPlayer> playersInFrame(ItemStack camera) {
        if (capturedPlayers != null) return capturedPlayers;
        if (cameraStandId >= 0) return projectedPlayersInFrame(camera);
        return ExposureVisibility.playersInFrame(source, camera).stream()
                .filter(player -> player instanceof ServerPlayer)
                .map(player -> (ServerPlayer) player)
                .toList();
    }

    /** 按真实源世界玩家半径筛候选，把玩家投影进目标世界进行Exposure视锥/遮挡判定，再按目标镜头距离排序。 */
    private List<ServerPlayer> projectedPlayersInFrame(ItemStack camera) {
        if (!(camera.getItem() instanceof CameraItem item)) return List.of();
        ServerLevel sourceLevel = (ServerLevel) source.asHolderEntity().level();
        var view = item.getPointOfView(this, camera);
        double fov = item.getViewfinderFov(remoteLevel, camera);
        double radius = ShuttershadowConfig.standPlayerRadius();
        return sourceLevel.players().stream()
                .filter(player -> player.level() == sourceLevel && player.isAlive() && !player.isRemoved())
                .filter(player -> player.position().distanceToSqr(sourceOrigin) <= radius * radius)
                .filter(player -> ExposureVisibility.isVisible(view, projectedPlayer(player), fov))
                .sorted(Comparator.comparingDouble(player -> view.pos().distanceToSqr(targetPosition(player))))
                .toList();
    }

    /** 复制并冻结此照片的出镜玩家名单，避免等待期间移动改变传送对象。 */
    public List<ServerPlayer> freezePlayersInFrame(List<ServerPlayer> players) {
        capturedPlayers = List.copyOf(players);
        return capturedPlayers;
    }

    /** 把Holder的摄影师引用换为当前在线ServerPlayer，防止复活前旧对象被使用。 */
    private static ServerPlayer resolveExecutingPlayer(CameraHolder holder) {
        ServerPlayer executing = holder.getServerPlayerExecutingExposure().orElse(null);
        if (executing == null) return null;
        ServerPlayer current = executing.getServer().getPlayerList().getPlayer(executing.getUUID());
        return current != null ? current : executing;
    }
    /** 玩家相对源原点的偏移映射到目标原点，Y偏移保持原值。 */
    public Vec3 targetPosition(ServerPlayer player) {
        Vec3 delta = player.position().subtract(sourceOrigin);
        return DimensionFilters.mapRelative(delta, targetOrigin, coordinateScale, 0.0D);
    }

    /** 目标位置相对目标原点的偏移按比例反算回真实源相机位置。 */
    public Vec3 sourcePosition(Vec3 targetPosition) {
        Vec3 delta = targetPosition.subtract(targetOrigin);
        // 手持相机可在打开后移动；当前 targetOrigin 对应当前源位置，而非打开时的 sourceOrigin。
        return source.asHolderEntity().position().add(
                delta.x / coordinateScale, delta.y, delta.z / coordinateScale);
    }

    /** 创建目标世界中模拟该玩家尺寸、朝向和位置的Observation供遮挡判定。 */
    public Entity projectedPlayer(ServerPlayer player) {
        return new Observation(remoteLevel, player, targetPosition(player));
    }

    /** 返回目标观察实体给Exposure当拍摄Holder。 */
    @Override
    public Entity asHolderEntity() { return observation; }

    /** 保持真实来源照片作者。 */
    @Override
    public Entity getExposureAuthorEntity() { return source.getExposureAuthorEntity(); }

    /** 转发来源玩家摄影师。 */
    @Override
    public Optional<Player> getPlayerExecutingExposure() { return source.getPlayerExecutingExposure(); }

    /** 返回经过当前在线身份修正的服务端摄影师。 */
    @Override
    public Optional<ServerPlayer> getServerPlayerExecutingExposure() {
        return Optional.ofNullable(resolveExecutingPlayer(source));
    }

    /** 转发来源奖励玩家。 */
    @Override
    public Optional<Player> getPlayerAwardedForExposure() { return source.getPlayerAwardedForExposure(); }

    /** 转发来源服务端奖励玩家。 */
    @Override
    public Optional<ServerPlayer> getServerPlayerAwardedForExposure() { return source.getServerPlayerAwardedForExposure(); }

    /** 转发来源相机操作者。 */
    @Override
    public Optional<CameraOperator> getExposureCameraOperator() { return source.getExposureCameraOperator(); }

    /** 仅供拍摄计算的MARKER实体，不spawn。 */
    private static final class Observation extends Marker {
        private final EntityDimensions observerDimensions;

        /** 在目标世界建立MARKER，保存带源眼高的尺寸并设置姿态、脚底位置和转角。 */
        private Observation(ServerLevel level, Entity source, Vec3 feet) {
            super(EntityType.MARKER, level);
            observerDimensions = source.getDimensions(source.getPose()).withEyeHeight(source.getEyeHeight());
            setPose(source.getPose());
            refreshDimensions();
            setPos(feet);
            setXRot(source.getXRot());
            setYRot(source.getYRot());
        }

        /** 返回复制的观察者尺寸。 */
        @Override
        public EntityDimensions getDimensions(Pose pose) {
            return observerDimensions != null ? observerDimensions : super.getDimensions(pose);
        }

        /** 只在眼睛所在区块已加载时检测水流体高度，未加载区块返回false，避免为水下标记加载远区块。 */
        @Override
        public boolean isUnderWater() {
            Vec3 eye = getEyePosition();
            BlockPos pos = BlockPos.containing(eye);
            if (!level().hasChunkAt(pos)) return false;
            var fluid = level().getFluidState(pos);
            return fluid.is(FluidTags.WATER) && eye.y < pos.getY() + fluid.getHeight(level(), pos);
        }
    }
}

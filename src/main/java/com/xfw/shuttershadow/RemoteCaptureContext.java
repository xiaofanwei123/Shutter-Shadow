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

/**
 * A single shutter's authoritative world/pose for Exposure's existing probes.
 * The observation object is never spawned, tracked, saved or ticked. The real
 * player remains the author, operator and owner of the camera and its film.
 */
public final class RemoteCaptureContext implements CameraHolder {
    private final CameraHolder source;
    private final Entity observation;
    private final ServerLevel remoteLevel;
    private final double coordinateScale;
    private final Vec3 sourceOrigin;
    private final Vec3 targetOrigin;
    private final int cameraStandId;
    private List<ServerPlayer> capturedPlayers;

    private RemoteCaptureContext(CameraHolder source, RemoteCameraSession session,
                                 ServerPlayer player, Entity cameraEntity) {
        this(source, cameraEntity, session.remoteLevel(), session.coordinateScale(),
                session.sourceOrigin(), session.targetCameraPosition(player), session.cameraStandId());
    }

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

    /** Returns null for ordinary cameras and unvalidated/expired sessions. */
    public static RemoteCaptureContext resolve(CameraHolder source, ItemStack camera) {
        // 红石照片只查询相机所在维度；目标上下文仅由传送入口单独取得。
        if (source.asHolderEntity() instanceof CameraStandEntity stand
                && RemoteStandPreparation.isRedstoneCapture(stand)) return null;
        RemoteCaptureContext prepared = RemoteStandPreparation.preparedContext(source, camera);
        return prepared != null ? prepared : resolveRoute(source, camera);
    }

    /** 传送目标与照片世界独立，红石不再把目标维度用于照片或光照元数据。 */
    public static RemoteCaptureContext resolveForTransfer(CameraHolder source, ItemStack camera) {
        return resolveRoute(source, camera);
    }

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

    public CameraHolder source() { return source; }
    public ServerLevel level() { return remoteLevel; }
    public int cameraStandId() { return cameraStandId; }
    public double coordinateScale() { return coordinateScale; }

    /** 同一组 Exposure 资格玩家用于照片实体信息和拍摄完成后的传送。 */
    public List<ServerPlayer> playersInFrame(ItemStack camera) {
        if (capturedPlayers != null) return capturedPlayers;
        if (cameraStandId >= 0) return projectedPlayersInFrame(camera);
        return ExposureVisibility.playersInFrame(source, camera).stream()
                .filter(player -> player instanceof ServerPlayer)
                .map(player -> (ServerPlayer) player)
                .toList();
    }

    /** 源范围限制真实玩家；远景资格按同一映射后的眼位、焦距与目标遮挡判断。 */
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

    /** 加载就绪、开始曝光时固定名单，后续查询复用，不纳入后来走进镜头的人。 */
    public List<ServerPlayer> freezePlayersInFrame(List<ServerPlayer> players) {
        capturedPlayers = List.copyOf(players);
        return capturedPlayers;
    }

    /** 复用 Exposure 的执行玩家查询；支架的操作玩家优先级由支架 Mixin 统一处理。 */
    private static ServerPlayer resolveExecutingPlayer(CameraHolder holder) {
        ServerPlayer executing = holder.getServerPlayerExecutingExposure().orElse(null);
        if (executing == null) return null;
        ServerPlayer current = executing.getServer().getPlayerList().getPlayer(executing.getUUID());
        return current != null ? current : executing;
    }
    public Vec3 targetPosition(ServerPlayer player) {
        Vec3 delta = player.position().subtract(sourceOrigin);
        return DimensionFilters.mapRelative(delta, targetOrigin, coordinateScale, 0.0D);
    }

    /** 将目标脚下位置逆向映射回快门瞬间的源相机位置，Y 不缩放。 */
    public Vec3 sourcePosition(Vec3 targetPosition) {
        Vec3 delta = targetPosition.subtract(targetOrigin);
        // 手持相机可在打开后移动；当前 targetOrigin 对应当前源位置，而非打开时的 sourceOrigin。
        return source.asHolderEntity().position().add(
                delta.x / coordinateScale, delta.y, delta.z / coordinateScale);
    }

    /**
     * Creates an unsaved, untracked target-level probe with the real player's
     * current pose and dimensions. It exists only for capture visibility tests.
     */
    public Entity projectedPlayer(ServerPlayer player) {
        return new Observation(remoteLevel, player, targetPosition(player));
    }

    @Override
    public Entity asHolderEntity() { return observation; }

    @Override
    public Entity getExposureAuthorEntity() { return source.getExposureAuthorEntity(); }

    @Override
    public Optional<Player> getPlayerExecutingExposure() { return source.getPlayerExecutingExposure(); }

    @Override
    public Optional<ServerPlayer> getServerPlayerExecutingExposure() {
        return Optional.ofNullable(resolveExecutingPlayer(source));
    }

    @Override
    public Optional<Player> getPlayerAwardedForExposure() { return source.getPlayerAwardedForExposure(); }

    @Override
    public Optional<ServerPlayer> getServerPlayerAwardedForExposure() { return source.getServerPlayerAwardedForExposure(); }

    @Override
    public Optional<CameraOperator> getExposureCameraOperator() { return source.getExposureCameraOperator(); }

    private static final class Observation extends Marker {
        private final EntityDimensions observerDimensions;

        private Observation(ServerLevel level, Entity source, Vec3 feet) {
            super(EntityType.MARKER, level);
            observerDimensions = source.getDimensions(source.getPose()).withEyeHeight(source.getEyeHeight());
            setPose(source.getPose());
            refreshDimensions();
            setPos(feet);
            setXRot(source.getXRot());
            setYRot(source.getYRot());
        }

        @Override
        public EntityDimensions getDimensions(Pose pose) {
            return observerDimensions != null ? observerDimensions : super.getDimensions(pose);
        }

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

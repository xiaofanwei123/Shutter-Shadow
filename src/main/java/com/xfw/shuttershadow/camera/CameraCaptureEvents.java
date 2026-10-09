package com.xfw.shuttershadow.camera;

import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.ShuttershadowConfig;
import com.xfw.shuttershadow.api.CameraCaptureContext;
import com.xfw.shuttershadow.api.CameraCapturePlan;
import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.api.event.CameraCaptureEvent;
import com.xfw.shuttershadow.api.event.CameraFrameEvent;
import com.xfw.shuttershadow.api.event.CameraSubjectsEvent;
import com.xfw.shuttershadow.network.RemoteCameraSession;
import com.xfw.shuttershadow.network.RemoteSceneStartS2C;
import com.xfw.shuttershadow.network.RemoteStandPreparation;
import com.xfw.shuttershadow.util.CaptureEntitySearchRange;
import io.github.mortuusars.exposure.util.PointOfView;
import io.github.mortuusars.exposure.world.camera.capture.CaptureParameters;
import io.github.mortuusars.exposure.world.camera.frame.EntitiesInFrame;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 固定每次拍摄计划并连接事件、异步截图、上传与传送结果。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class CameraCaptureEvents {
    private static final Map<ItemStack, Shot> SHOTS = new IdentityHashMap<>();
    private static final Map<UUID, Shot> ALL = new java.util.HashMap<>();
    private static final Map<ServerPlayer, Map<String, Shot>> IMAGE_SHOTS = new IdentityHashMap<>();
    private static final Map<ServerPlayer, Map<Long, Shot>> CAPTURE_SHOTS = new IdentityHashMap<>();
    private static final ThreadLocal<Shot> EXECUTING = new ThreadLocal<>();
    /** 一次拍摄跨多个调用保持同一计划，收尾后才从索引移除。 */
    private static final class Shot {
        final CameraCaptureContext context;
        final CameraCapturePlan plan;
        final ItemStack camera;
        final List<UUID> transferred = new ArrayList<>();
        List<ServerPlayer> projectedPhotoSubjects = List.of();
        String exposureId;
        RemoteSceneStartS2C customScene;
        boolean nativeReturned;
        boolean uploaded;
        boolean filmWritten;
        boolean standHeld;
        int ticks;
        final Map<RemoteKey, RemoteCaptureContext> remotes = new java.util.HashMap<>();
        QueryKey photoQuery;
        List<LivingEntity> photoCandidates = List.of();
        /** 保存固定计划与真实物品引用。 */
        Shot(CameraCaptureContext context, CameraCapturePlan plan, ItemStack camera) {
            this.context = context; this.plan = plan; this.camera = camera;
        }
    }
    /** 区分同维度不同镜头位置，照片和生物查询不能错误共用锚点。 */
    private record RemoteKey(ResourceLocation dimension, Vec3 position) {}
    /** 只有镜头、视锥和范围全部一致时复用原始查询，两个选择事件仍独立执行。 */
    private record QueryKey(CameraHolder holder, PointOfView view, double fov, Integer radius) {}
    /** 禁止实例化流程协调器。 */
    private CameraCaptureEvents() {}

    /** 首次受理发布前置事件，异步恢复复用既有计划。 */
    public static boolean begin(CameraItem item, CameraHolder holder, ServerPlayer player, ItemStack camera) {
        Shot pending = SHOTS.get(camera);
        if (pending != null) return !pending.nativeReturned && pending.context.getExecutor() == player
                && pending.context.getHolder().asHolderEntity() == holder.asHolderEntity();
        boolean stand = holder.asHolderEntity() instanceof CameraStandEntity;
        boolean redstone = stand && RemoteStandPreparation.isRedstoneCapture((CameraStandEntity) holder.asHolderEntity());
        RemoteCaptureContext remote = RemoteCaptureContext.resolveForTransfer(holder, camera);
        ResourceLocation observed = remote == null ? null : remote.level().dimension().location();
        CameraCaptureContext context = new CameraCaptureContext(redstone ? CameraCaptureContext.Trigger.REDSTONE
                : stand ? CameraCaptureContext.Trigger.MANUAL_STAND : CameraCaptureContext.Trigger.HANDHELD,
                holder, player, camera, !stand && item.isInSelfieMode(camera), observed);
        CameraCapturePlan plan = defaultPlan(context, camera);
        Shot created = new Shot(context, plan, camera);
        SHOTS.put(camera, created);
        ALL.put(context.getShotId(), created);
        try {
            CameraCaptureEvent.Before before = NeoForge.EVENT_BUS.post(new CameraCaptureEvent.Before(context, plan));
            plan.freeze();
            if (before.isCanceled()) {
                finish(created, CameraCaptureEvent.Result.CANCELED, "拍摄前事件取消");
                return false;
            }
            if (level(created, plan.getPhotoDimension()) == null
                    || plan.isPlayerTransfer() && level(created, plan.getPlayerDimension()) == null
                    || plan.isMobTransfer() && (level(created, plan.getMobQueryDimension()) == null
                    || level(created, plan.getMobDimension()) == null)) {
                finish(created, CameraCaptureEvent.Result.FAILED, "拍摄计划指定的维度不存在");
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            if (SHOTS.get(camera) == created) finish(created, CameraCaptureEvent.Result.FAILED, "拍摄前监听异常");
            throw exception;
        }
    }
    /** 按触发方式和胶卷建立默认计划，红石照片采用来源维度。 */
    private static CameraCapturePlan defaultPlan(CameraCaptureContext context, ItemStack camera) {
        ResourceLocation source = context.getSourceLevel().dimension().location();
        ResourceLocation observed = context.getObservationDimension();
        return new CameraCapturePlan(CameraCapturePlan.PhotoOutput.PHOTO,
                context.getTrigger() != CameraCaptureContext.Trigger.REDSTONE && observed != null ? observed : source,
                observed, observed != null && DimensionFilmCapture.hasPlayerDimensionFilm(camera),
                observed != null && MobDimensionFilmCapture.hasMobDimensionFilm(camera),
                ShuttershadowConfig.mobCaptureRadius(), source);
    }
    /** 曝光失效通过本模组拍摄前事件选择免成片，其他监听者仍可修改计划。 */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void applyEnchantments(CameraCaptureEvent.Before event) {
        if (CameraEnchantments.has(event.getContext().getCamera(), CameraEnchantments.EXPOSURE_FAILURE)) {
            event.getPlan().setPhotoOutput(CameraCapturePlan.PhotoOutput.NO_IMAGE);
        }
    }
    /** 返回正在执行的固定上下文，非相机事务为空。 */
    public static @Nullable CameraCaptureContext context(ItemStack camera) {
        Shot shot = shot(camera); return shot == null ? null : shot.context;
    }
    /** 返回本次冻结的拍摄计划。 */
    public static @Nullable CameraCapturePlan plan(ItemStack camera) {
        Shot shot = shot(camera); return shot == null ? null : shot.plan;
    }
    /** 返回是否免成片，事务外仅用于保留原附魔行为。 */
    public static boolean discardsImage(ItemStack camera) {
        CameraCapturePlan plan = plan(camera);
        return plan == null ? CameraEnchantments.has(camera, CameraEnchantments.EXPOSURE_FAILURE)
                : plan.getPhotoOutput() == CameraCapturePlan.PhotoOutput.NO_IMAGE;
    }
    /** 返回是否按本次计划传送玩家。 */
    public static boolean transfersPlayers(ItemStack camera) {
        CameraCapturePlan plan = plan(camera); return plan != null && plan.isPlayerTransfer();
    }
    /** 返回是否按本次计划传送生物。 */
    public static boolean transfersMobs(ItemStack camera) {
        CameraCapturePlan plan = plan(camera); return plan != null && plan.isMobTransfer();
    }
    /** 返回本次生物查询半径。 */
    public static int mobRadius(ItemStack camera) {
        CameraCapturePlan plan = plan(camera); return plan == null ? ShuttershadowConfig.mobCaptureRadius() : plan.getMobCaptureRadius();
    }
    /** 按固定计划查找服务端真实维度实例。 */
    private static @Nullable ServerLevel level(Shot shot, @Nullable ResourceLocation dimension) {
        return dimension == null ? null : shot.context.getExecutor().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }
    /** 异步上传回调绑定原拍摄，避免同一相机新照片覆盖旧照片的传送计划。 */
    public static boolean withContext(CameraCaptureContext context, Runnable action) {
        Shot previous = EXECUTING.get();
        Shot current = ALL.get(context.getShotId());
        if (current == null) return false;
        EXECUTING.set(current);
        try { action.run(); }
        finally { if (previous == null) EXECUTING.remove(); else EXECUTING.set(previous); }
        return true;
    }
    /** 同步调用取当前物品，上传调用优先取固定拍摄作用域。 */
    private static Shot shot(ItemStack camera) {
        Shot executing = EXECUTING.get();
        return executing != null && executing.camera == camera ? executing : SHOTS.get(camera);
    }
    /** 更新图片回执索引，编号变化时只撤销属于原拍摄的旧记录。 */
    private static void setExposureId(Shot shot, @Nullable String id) {
        if (ALL.get(shot.context.getShotId()) != shot || Objects.equals(shot.exposureId, id)) return;
        removeIndex(IMAGE_SHOTS, shot.exposureId, shot);
        shot.exposureId = id;
        if (id != null) IMAGE_SHOTS.computeIfAbsent(shot.context.getExecutor(), ignored -> new HashMap<>()).put(id, shot);
    }
    /** 按玩家真实实例和回执编号定位拍摄，重连后的同 UUID 玩家不能命中旧事务。 */
    private static <K> @Nullable Shot findIndexed(Map<ServerPlayer, Map<K, Shot>> index, ServerPlayer player, K key) {
        Map<K, Shot> shots = index.get(player);
        return shots == null ? null : shots.get(key);
    }
    /** 条件移除索引，空玩家记录随即释放，不影响重入后建立的新拍摄。 */
    private static <K> void removeIndex(Map<ServerPlayer, Map<K, Shot>> index, @Nullable K key, Shot shot) {
        if (key == null) return;
        ServerPlayer player = shot.context.getExecutor();
        Map<K, Shot> shots = index.get(player);
        if (shots != null && shots.remove(key, shot) && shots.isEmpty()) index.remove(player, shots);
    }
    /** 创建独立的照片世界上下文，先换维自拍留在真实玩家世界拍摄。 */
    public static @Nullable RemoteCaptureContext photoContext(CameraHolder holder, ItemStack camera) {
        Shot shot = shot(camera);
        if (shot == null) return RemoteCaptureContext.resolve(holder, camera);
        ServerLevel target = level(shot, shot.plan.getPhotoDimension());
        if (target == null || target == holder.asHolderEntity().level() && !shot.plan.isPhotoSceneChanged()) return null;
        return remote(shot, target, shot.plan.getPhotoPosition());
    }
    /** 创建玩家传送查询上下文，照片维度不参与决定目的地。 */
    public static @Nullable RemoteCaptureContext playerContext(ItemStack camera) {
        Shot shot = shot(camera);
        return shot == null || !shot.plan.isPlayerTransfer() ? null
                : remote(shot, level(shot, shot.plan.getPlayerDimension()), null);
    }
    /** 创建生物查询上下文，红石照片仍可留在来源世界。 */
    public static @Nullable RemoteCaptureContext mobContext(ItemStack camera) {
        Shot shot = shot(camera);
        return shot == null || !shot.plan.isMobTransfer() ? null
                : remote(shot, level(shot, shot.plan.getMobQueryDimension()), null);
    }
    /** 优先沿用实际观察锚点，否则按原生维度比例建立镜头。 */
    private static @Nullable RemoteCaptureContext remote(Shot shot, @Nullable ServerLevel target, @Nullable Vec3 position) {
        if (target == null) return null;
        RemoteKey key = new RemoteKey(target.dimension().location(), position);
        RemoteCaptureContext cached = shot.remotes.get(key);
        if (cached != null) return cached;
        RemoteCaptureContext observed = RemoteCaptureContext.resolveForTransfer(shot.context.getHolder(), shot.camera);
        if (position == null && observed != null && observed.level() == target) {
            shot.remotes.put(key, observed); return observed;
        }
        double scale = DimensionFilters.horizontalScale(shot.context.getSourceLevel(), target);
        RemoteCaptureContext created = RemoteCaptureContext.create(shot.context.getHolder(), target,
                position != null ? position : DimensionFilters.mapAbsolute(shot.context.getSourcePosition(), scale), scale,
                shot.context.getSourcePosition());
        shot.remotes.put(key, created); return created;
    }
    /** 给普通手持自定义照片场景建立后台截图订阅，默认预览继续沿用原截图。 */
    public static CaptureParameters parameters(CameraHolder holder, ServerPlayer player, ItemStack camera, CaptureParameters parameters) {
        Shot shot = SHOTS.get(camera);
        if (shot == null) return parameters;
        setExposureId(shot, parameters.exposureId());
        RemoteSceneStartS2C scene = null;
        boolean custom = shot.plan.isPhotoSceneChanged()
                || !holder.asHolderEntity().level().dimension().location().equals(shot.plan.getPhotoDimension())
                && shot.context.isSelfie() && shot.plan.isPlayerTransfer()
                || !shot.plan.getPhotoDimension().equals(shot.context.getObservationDimension() == null
                ? shot.context.getSourceLevel().dimension().location() : shot.context.getObservationDimension());
        if (!(holder.asHolderEntity() instanceof CameraStandEntity) && custom && !discardsImage(camera)
                && parameters.projection().isEmpty()) {
            RemoteCaptureContext remote = photoContext(holder, camera);
            if (remote != null) {
                if (shot.customScene == null) {
                    shot.customScene = RemoteCameraSession.openCapture(player, remote);
                    if (ALL.get(shot.context.getShotId()) != shot) {
                        RemoteCameraSession.close(player, shot.customScene.sequence());
                        return parameters;
                    }
                    CAPTURE_SHOTS.computeIfAbsent(shot.context.getExecutor(), ignored -> new HashMap<>())
                            .put(shot.customScene.sequence(), shot);
                }
                RemoteCameraSession.flushCapture(player);
                scene = shot.customScene;
            }
        }
        if (!shot.plan.isShaderChanged() && scene == null) return parameters;
        var builder = parameters.mutable();
        if (shot.plan.isShaderChanged()) builder.setFilter(shot.plan.getFilterShader());
        if (scene != null) builder.extraData(RemoteSceneStartS2C.CAPTURE_SCENE, scene);
        return builder.build();
    }
    /** 发布照片对象选择并保存真实实体名单。 */
    public static List<LivingEntity> photoSubjects(CameraHolder holder, ItemStack camera, PointOfView view,
            double fov, @Nullable Integer radius, List<LivingEntity> candidates) {
        Shot shot = SHOTS.get(camera);
        if (shot == null) return candidates;
        if (shot.plan.isMobTransfer()) {
            shot.photoQuery = new QueryKey(holder, view, fov, radius);
            shot.photoCandidates = List.copyOf(candidates);
        }
        List<LivingEntity> all = new ArrayList<>(candidates);
        List<ServerPlayer> projected = holder instanceof RemoteCaptureContext remote && remote.cameraStandId() >= 0
                ? remote.playersInFrame(camera) : List.of();
        all.addAll(projected);
        List<LivingEntity> defaults = RemoteStandPreparation.hidesPhotoPlayers(shot.context, camera)
                ? all.stream().filter(entity -> !(entity instanceof Player)).toList() : all;
        List<LivingEntity> selected = select(shot, CameraSubjectsEvent.Purpose.PHOTO, all, defaults);
        shot.projectedPhotoSubjects = projected.stream().filter(selected::contains).toList();
        return selected.stream().filter(candidates::contains).toList();
    }
    /** 返回照片选择事件批准的源维度投影玩家。 */
    public static List<ServerPlayer> projectedPhotoPlayers(ItemStack camera) {
        Shot shot = shot(camera); return shot == null ? List.of() : shot.projectedPhotoSubjects;
    }
    /** 发布玩家传送选择，保留存活、在线和个人同意校验。 */
    public static List<ServerPlayer> selectPlayers(ItemStack camera, List<ServerPlayer> candidates) {
        Shot shot = SHOTS.get(camera);
        if (shot == null || !shot.plan.isPlayerTransfer()) return List.of();
        return select(shot, CameraSubjectsEvent.Purpose.PLAYER_TRANSFER, candidates, candidates).stream()
                .filter(entity -> entity instanceof ServerPlayer).map(entity -> (ServerPlayer) entity)
                .filter(player -> shot.context.getTrigger() == CameraCaptureContext.Trigger.HANDHELD
                        || DimensionFilmCapture.acceptsStandTeleport(player)).toList();
    }
    /** 查询生物并发布选择事件，默认只选首个，监听者可选择多个候选。 */
    public static List<LivingEntity> selectMobs(ItemStack camera, RemoteCaptureContext remote) {
        Shot shot = SHOTS.get(camera);
        if (shot == null || !(camera.getItem() instanceof CameraItem item)) return List.of();
        PointOfView view = item.getPointOfView(remote, camera);
        double fov = item.getViewfinderFov(remote.level(), camera);
        int radius = shot.plan.getMobCaptureRadius();
        List<LivingEntity> raw = new QueryKey(remote, view, fov, radius).equals(shot.photoQuery)
                ? shot.photoCandidates : CaptureEntitySearchRange.withRadius(radius,
                () -> EntitiesInFrame.get(remote, view, fov));
        List<LivingEntity> candidates = raw.stream().filter(entity -> !(entity instanceof Player)).toList();
        return select(shot, CameraSubjectsEvent.Purpose.MOB_TRANSFER, candidates,
                candidates.isEmpty() ? List.of() : List.of(candidates.getFirst()));
    }
    /** 发布本阶段名单并过滤事件后已经失效的对象。 */
    private static List<LivingEntity> select(Shot shot, CameraSubjectsEvent.Purpose purpose,
            List<? extends LivingEntity> candidates, List<? extends LivingEntity> defaults) {
        CameraSubjectsEvent event = NeoForge.EVENT_BUS.post(new CameraSubjectsEvent(shot.context, purpose, candidates, defaults));
        return event.getSubjects().stream()
                .filter(entity -> entity.isAlive() && !entity.isRemoved()).toList();
    }
    /** 在整帧生成后发布我们的修改事件，随后准备生物上传事务。 */
    public static Frame frame(ItemStack camera, Frame frame) {
        Shot shot = SHOTS.get(camera);
        if (shot == null) return frame;
        setExposureId(shot, frame.identifier().id());
        CameraFrameEvent event = NeoForge.EVENT_BUS.post(new CameraFrameEvent(shot.context, frame));
        Frame result = event.getFrame();
        if (shot.plan.isMobTransfer()) MobDimensionFilmCapture.prepare(camera, result);
        return result;
    }
    /** 返回玩家固定目的地或按每名玩家原生比例映射。 */
    public static Vec3 playerDestination(ItemStack camera, ServerPlayer player, ServerLevel target) {
        Shot shot = SHOTS.get(camera);
        if (shot == null) return player.position();
        if (shot.plan.getPlayerPosition() != null) return shot.plan.getPlayerPosition();
        RemoteCaptureContext remote = playerContext(camera);
        return remote != null ? remote.targetPosition(player)
                : DimensionFilters.mapAbsolute(player.position(), DimensionFilters.horizontalScale(player.level(), target));
    }
    /** 返回生物传送的目标世界。 */
    public static ServerLevel mobDestinationLevel(ItemStack camera, ServerLevel fallback) {
        Shot shot = SHOTS.get(camera); return shot == null ? fallback : level(shot, shot.plan.getMobDimension());
    }
    /** 返回生物目的地，默认回来源镜头映射，自定义世界则使用原生比例。 */
    public static Vec3 mobDestination(ItemStack camera, RemoteCaptureContext remote, Entity entity) {
        Shot shot = SHOTS.get(camera);
        if (shot != null && shot.plan.getMobPosition() != null) return shot.plan.getMobPosition();
        ServerLevel target = mobDestinationLevel(camera, (ServerLevel) remote.source().asHolderEntity().level());
        if (shot == null) return remote.sourcePosition(entity.position());
        if (target == shot.context.getSourceLevel()) {
            Entity holder = shot.context.getHolder().asHolderEntity();
            Vec3 anchor = holder.level() == target ? holder.position() : shot.context.getSourcePosition();
            return remote.sourcePosition(entity.position(), anchor);
        }
        return DimensionFilters.mapAbsolute(entity.position(), DimensionFilters.horizontalScale(entity.level(), target));
    }
    /** 记录真正写入胶卷，延迟提交也调用此处。 */
    public static void filmWritten(ItemStack camera) { Shot shot = shot(camera); if (shot != null) shot.filmWritten = true; }
    /** 标记支架尚未完成，上传先到也不提前报告终态。 */
    public static void holdStand(ItemStack camera) { Shot shot = SHOTS.get(camera); if (shot != null) shot.standHeld = true; }
    /** 支架完成动作后解除终态等待。 */
    public static void standFinished(ItemStack camera) {
        Shot shot = SHOTS.get(camera);
        if (shot != null) { shot.standHeld = false; SHOTS.remove(camera, shot); completeIfReady(shot); }
    }
    /** 原生方法返回只标记阶段，不等于图片完成。 */
    public static void nativeReturned(ItemStack camera) {
        Shot shot = shot(camera);
        if (shot != null) {
            shot.nativeReturned = true;
            if (!shot.standHeld) SHOTS.remove(camera, shot);
            completeIfReady(shot);
        }
    }
    /** 只接受本次摄影师与图片编号对应的服务端上传成功节点。 */
    public static void uploaded(ServerPlayer player, String id) {
        Shot shot = findIndexed(IMAGE_SHOTS, player, id);
        if (shot != null) {
            shot.uploaded = true; completeIfReady(shot);
        }
    }
    /** 自定义手持后台截图失败时按服务端保存的所有者和序号终结。 */
    public static void captureFinished(ServerPlayer player, long sequence, boolean captured) {
        if (captured) return;
        Shot shot = findIndexed(CAPTURE_SHOTS, player, sequence);
        if (shot != null) finish(shot, CameraCaptureEvent.Result.FAILED, "自定义场景截图失败");
    }
    /** 按服务端保存的执行者和编号接受客户端图片失败，不能终结别人的拍摄。 */
    public static void imageFailed(ServerPlayer player, String id) {
        Shot shot = findIndexed(IMAGE_SHOTS, player, id);
        if (shot != null) finish(shot, CameraCaptureEvent.Result.FAILED, "客户端图片生成或处理失败");
    }
    /** 记录传送主体，仅报告真实成功结果。 */
    public static void recordTransfer(ItemStack camera, Entity original, Entity moved) {
        Shot shot = shot(camera);
        if (shot != null && moved != null && !shot.transferred.contains(original.getUUID())) shot.transferred.add(original.getUUID());
    }
    /** 截图失败和异步取消统一终结，不重复完成。 */
    public static void failed(ItemStack camera, String reason) { Shot shot = SHOTS.get(camera); if (shot != null) finish(shot, CameraCaptureEvent.Result.FAILED, reason); }
    /** 异步失败仅终结原拍摄，完成监听创建的新拍摄不受旧回调影响。 */
    public static void failed(@Nullable CameraCaptureContext context, String reason) {
        Shot shot = context == null ? null : ALL.get(context.getShotId());
        if (shot != null) finish(shot, CameraCaptureEvent.Result.FAILED, reason);
    }
    /** 成片等待上传接收，免成片等待原生及支架动作结束。 */
    private static void completeIfReady(Shot shot) {
        if (!shot.nativeReturned || shot.standHeld) return;
        if (shot.plan.getPhotoOutput() == CameraCapturePlan.PhotoOutput.NO_IMAGE) finish(shot, CameraCaptureEvent.Result.NO_IMAGE, "");
        else if (shot.uploaded) finish(shot, CameraCaptureEvent.Result.IMAGE_RECEIVED, "");
    }
    /** 先移除索引再通知完成，监听者重入不会完成旧拍摄两次。 */
    private static void finish(Shot shot, CameraCaptureEvent.Result result, String reason) {
        if (!ALL.remove(shot.context.getShotId(), shot)) return;
        SHOTS.remove(shot.camera, shot);
        removeIndex(IMAGE_SHOTS, shot.exposureId, shot);
        if (shot.customScene != null) removeIndex(CAPTURE_SHOTS, shot.customScene.sequence(), shot);
        if (result == CameraCaptureEvent.Result.FAILED && !shot.transferred.isEmpty()) {
            reason = "已完成传送，后续拍摄处理失败：" + reason;
        }
        if (result == CameraCaptureEvent.Result.FAILED || result == CameraCaptureEvent.Result.CANCELED) {
            RemoteStandPreparation.cancelCapture(shot.context, shot.camera);
            if (shot.exposureId != null) {
                MobDimensionFilmCapture.cancelPending(shot.context.getExecutor(), shot.exposureId);
                ((RemoteStandPreparation.UploadWindow) io.github.mortuusars.exposure.ExposureServer.exposureRepository())
                        .shuttershadow$cancelExpected(shot.context.getExecutor(), shot.exposureId);
            }
        }
        if (shot.customScene != null) RemoteCameraSession.close(shot.context.getExecutor(), shot.customScene.sequence());
        NeoForge.EVENT_BUS.post(new CameraCaptureEvent.Completed(shot.context, result, shot.uploaded,
                shot.filmWritten, shot.transferred, reason));
    }
    /** 清理失去执行者和超时的拍摄，不使用墙钟消耗暂停世界的额度。 */
    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        for (Shot shot : List.copyOf(ALL.values())) {
            ServerPlayer player = shot.context.getExecutor();
            if (!player.isAlive() || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                    || ++shot.ticks > 4800) finish(shot, CameraCaptureEvent.Result.FAILED, "执行者失效或拍摄超时");
        }
    }
    /** 玩家登出后仅清理属于该玩家实例的拍摄。 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        for (Shot shot : List.copyOf(ALL.values())) if (shot.context.getExecutor() == event.getEntity()) finish(shot, CameraCaptureEvent.Result.FAILED, "执行者退出");
    }
    /** 关服时清理所有拍摄，避免下一次进入世界继承旧计划。 */
    @SubscribeEvent
    public static void onStop(ServerStoppingEvent event) {
        for (Shot shot : List.copyOf(ALL.values())) finish(shot, CameraCaptureEvent.Result.FAILED, "服务器关闭");
    }
}

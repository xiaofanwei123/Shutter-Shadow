package com.xfw.shuttershadow.client;

import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.ShuttershadowConfig;
import com.xfw.shuttershadow.network.CameraSessionCloseC2S;
import com.xfw.shuttershadow.network.CameraSessionRequestC2S;
import com.xfw.shuttershadow.network.RemoteSceneStartS2C;
import com.xfw.shuttershadow.network.RemoteSceneStopS2C;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder;
import io.github.mortuusars.exposure.client.capture.task.BackgroundScreenshotCaptureTask;
import io.github.mortuusars.exposure.world.camera.CameraOnStand;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import io.github.mortuusars.exposure.world.item.camera.Attachment;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.opengl.GL11;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.access.IECamera;
import com.xfw.shuttershadow.core.render.MyGameRenderer;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

/**
 * Exposure 的客户端适配层。
 * <p>
 * 职责：把 Exposure 的取景器/截图流程桥接到本模组内核的远程世界渲染能力。
 * 远程维度、网格、实体由内核统一托管；此类只管理会话与渲染调用。
 * <p>
 * 生命周期：由 ClientTickEvent.Post 驱动，基于“滤镜映射”自动开启/关闭会话。
 */
@EventBusSubscriber(modid = Shuttershadow.MODID, value = Dist.CLIENT)
public final class ImmersiveCameraClient {

    /** 当前会话；null 表示未开启。所有字段的读写都在客户端主线程进行。 */
    private static Session session;

    /** 服务端确认后的远程场景信息；由 RemoteSceneStartS2C 包填充。 */
    private static RemoteSceneStartS2C scene;
    /** 仅在 Exposure 后台截图期间使用，普通游戏画面仍由取景器会话决定。 */
    private static CaptureSnapshot screenshot;
    private static CameraStandEntity screenshotStand;

    /** 单调递增的会话序号，用于匹配请求与响应，防止过期包污染新会话。 */
    private static long nextSequence;
    private static int requestRetryTicks;

    /** 渲染防重入标志：IP 的渲染会回调进本类，必须避免递归。 */
    private static boolean rendering;
    /** Suppresses reopening the synthetic scene while a dimension-film transfer is pending. */
    private static boolean suppressRemoteScene;
    /** Defers camera-entity cleanup until IP has finished changing ClientLevel. */
    private static int deferredCameraResetTicks;


    /**
     * 会话快照。绑定连接和源世界，一旦玩家断线或跨维度就立即失效。
     *
     * @param sequence     本次会话的序号（与网络包对齐）
     * @param viewfinder   触发会话的取景器实例（引用比较，用于检测更换）
     * @param mapping      当前滤镜对应的维度/缩放映射
     * @param connection   建立会话时的连接，用于检测重连
     * @param sourceLevel  建立会话时玩家所在的源世界
     */
    private record Session(long sequence, Viewfinder viewfinder, DimensionFilters.Route mapping,
                           int cameraStandId, ClientPacketListener connection, ClientLevel sourceLevel) {
        /** 会话是否仍然有效：玩家存在、连接未换、源世界未换。 */
        boolean valid() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && connection == mc.getConnection() && mc.player.level() == sourceLevel;
        }

        /** 一次性支架截图期间，真实玩家可以已被维度胶卷传送。 */
        boolean validForCapture() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && connection == mc.getConnection() && sourceLevel != null;
        }
    }

    /**
     * 单帧渲染所需的不可变数据。
     *
     * @param session       当前会话
     * @param scene         服务端下发的远程场景信息
     * @param cameraYOffset 相机物品带来的 Y 偏移（比如举镜高度）
     */
    private record CaptureSnapshot(Session session, RemoteSceneStartS2C scene, double cameraYOffset) {
        /** 相机和玩家投影共用坐标换算；X/Z 按倍率缩放，Y 只叠加偏移。 */
        Vec3 targetPosition(Vec3 sourcePosition, double yOffset) {
            return DimensionFilters.mapRelative(sourcePosition.subtract(scene.sourceOrigin()),
                    scene.position(), scene.coordinateScale(), yOffset);
        }
    }

    private ImmersiveCameraClient() {}

    /**
     * 收到服务端场景数据。仅在序号匹配当前会话时接受，避免过期响应。
     */
    public static void start(RemoteSceneStartS2C message) {
        // 无会话、会话已失效、或序号不匹配 → 丢弃
        if (session == null || !session.valid() || session.sequence() != message.sequence()
                || !session.mapping().dimension().equals(message.dimension())
                || !session.sourceLevel().dimension().location().equals(message.sourceDimension())) return;
        scene = message;
    }

    /** 服务端在 IP 传送包之后发送；独立的离屏截图不依赖取景器会话。 */
    public static void stopRemoteScene(RemoteSceneStopS2C message) {
        if (message.captureSequence() < 0) {
            RemoteStandCapture.cancel(message.captureSequence());
            SourceStandCapture.cancel(message.captureSequence());
        } else {
            stopRemoteScene();
        }
    }

    public static void stopRemoteScene() {
        session = null;
        scene = null;
        suppressRemoteScene = true;
        // 在 tick 边界清理已脱离源维度的支架相机，不改正在使用的截图相机。
        deferredCameraResetTicks = Math.max(deferredCameraResetTicks, 2);
    }

    /** Called by the Exposure stop-control packet mixin instead of mutating the camera mid-frame. */
    public static void deferExposureStandCameraReset() {
        deferredCameraResetTicks = Math.max(deferredCameraResetTicks, 1);
    }

    /**
     * 采集本帧渲染所需的快照。
     * <p>
     * 返回 null 表示“当前不应渲染远程场景”，调用方直接跳过即可。
     * 这是所有渲染/粒子路径的统一准入检查。
     */
    private static CaptureSnapshot captureSnapshot() {
        if (SourceStandCapture.isRenderingSourceScene()) return null;
        // 支架截图状态只存在于一次同步离屏绘制内。
        if (screenshot != null && BackgroundScreenshotCaptureTask.isCapturing()
                && screenshot.session().validForCapture()) return screenshot;
        // 五重前置条件：场景已到达、会话存在且有效、相机激活、取景器与建会话时一致
        if (scene == null || session == null || !session.valid() || !CameraClient.isActive()
                || CameraClient.viewfinder() != session.viewfinder()
                || !session.mapping().dimension().equals(scene.dimension())
                || !Objects.equals(session.mapping(), mappingFor(session.viewfinder()))) return null;
        // 从相机物品读取 Y 偏移；无则 0
        double offset = session.viewfinder().camera()
                .map((item, stack) -> item.getYPositionOffset(stack)).orElse(0.0D);
        return new CaptureSnapshot(session, scene, offset);
    }

    /** 手动支架照片绑定 Exposure 截图任务中的真实支架。 */
    static void beginScreenshot(RemoteSceneStartS2C scene, CameraStandEntity stand) {
        Minecraft mc = Minecraft.getInstance();
        ItemStack camera = stand.getCamera();
        double offset = camera.getItem() instanceof CameraItem item ? item.getYPositionOffset(camera) : 0.0D;
        screenshot = new CaptureSnapshot(new Session(scene.sequence(), null, null,
                stand.getId(), mc.getConnection(), (ClientLevel) stand.level()), scene, offset);
        screenshotStand = stand;
    }

    static void endScreenshot(long sequence) {
        if (screenshot != null && screenshot.scene().sequence() == sequence) {
            screenshot = null;
            screenshotStand = null;
        }
    }

    /** 只在同步离屏截图调用内有效，用于限定 Exposure 底层渲染适配的范围。 */
    public static boolean isStandScreenshot() {
        return screenshot != null;
    }

    /**
     * 关闭会话：通知服务端并清理本地状态。
     * <p>
     * 世界的销毁由内核连接生命周期管理，关闭取景器不销毁共享的远程世界。
     */
    private static void close() {
        // 仅当连接未变时才发包；断线/重连时连接已失效，发了也是错的
        if (session != null && session.connection() == Minecraft.getInstance().getConnection()) {
            PacketDistributor.sendToServer(new CameraSessionCloseC2S(session.sequence()));
        }
        session = null;
        scene = null;
        requestRetryTicks = 0;
        // Remote worlds may still be used by a pending screenshot or transfer.
    }

    /**
     * 计算远程相机位置。
     * <p>
     * 映射规则：以源世界原点为基准，X/Z 按 coordinateScale 缩放，Y 不缩放（仅加相机偏移）。
     * 这与原版维度传送的坐标语义一致。
     */
    private static Vec3 cameraPosition(CaptureSnapshot snapshot, float partialTick) {
        return snapshot.targetPosition(cameraAnchor(snapshot.session()).getEyePosition(partialTick),
                snapshot.cameraYOffset());
    }

    /** 预览与照片绘制相同的附近真实玩家，像素可见性由目标视锥和原生深度决定。 */
    static List<PlayerProjection> playerProjections(ClientLevel remote, Camera remoteCamera, float partialTick) {
        if (!rendering) return List.of();
        CaptureSnapshot snapshot = captureSnapshot();
        // 手持自拍仍由 Exposure 原生玩家视角与自拍流程处理。
        if (snapshot == null || snapshot.session().cameraStandId() < 0) return List.of();
        ClientLevel source = snapshot.session().sourceLevel();
        if (source == null) return List.of();

        // The entity hook can run during world join or after IP has popped its
        // render context. Never read the top entry from an empty IP stack.
        if (!WorldRenderInfo.isRendering()) return List.of();
        WorldRenderInfo renderInfo = WorldRenderInfo.getTopRenderInfo();
        if (renderInfo == null || renderInfo.world != remote
                || !remote.dimension().location().equals(snapshot.scene().dimension())) return List.of();

        // Exposure 的眼位判定用于实体记录和传送资格，不能控制整个模型的显示。
        // IP 已同步此范围的源玩家；取景器和离屏照片共用同一套绘制候选。
        List<AbstractClientPlayer> players = source.players();
        double radius = ShuttershadowConfig.standPlayerRadius();
        Vec3 cameraPos = renderInfo.cameraPos != null ? renderInfo.cameraPos : remoteCamera.getPosition();
        List<PlayerProjection> projections = new ArrayList<>(players.size());
        for (AbstractClientPlayer player : players) {
            if (player.level() != source || player.isRemoved() || !player.isAlive()) continue;
            // 沿用真实玩家的原版位置插值，不修改玩家坐标、姿势或所属世界。
            Vec3 position = new Vec3(
                    Mth.lerp(partialTick, player.xOld, player.getX()),
                    Mth.lerp(partialTick, player.yOld, player.getY()),
                    Mth.lerp(partialTick, player.zOld, player.getZ()));
            if (position.distanceToSqr(snapshot.scene().sourceOrigin()) > radius * radius) continue;
            projections.add(new PlayerProjection(player, remote,
                    snapshot.targetPosition(position, 0.0D), cameraPos));
        }
        return projections;
    }

    static record PlayerProjection(AbstractClientPlayer player, ClientLevel level, Vec3 position,
                                   Vec3 cameraPosition) {}

    /** Returns the local camera anchor: the stand for a stand camera, otherwise the active camera entity. */
    private static Entity cameraAnchor(Session session) {
        if (screenshot != null && screenshot.session() == session) return screenshotStand;
        Minecraft mc = Minecraft.getInstance();
        if (session.cameraStandId() >= 0 && session.sourceLevel() != null) {
            Entity stand = session.sourceLevel().getEntity(session.cameraStandId());
            if (stand != null) return stand;
        }
        return mc.getCameraEntity() == null ? mc.player : mc.getCameraEntity();
    }

    private static int cameraStandId(Viewfinder viewfinder) {
        if (viewfinder == null || viewfinder.camera() == null) return -1;
        return viewfinder.camera() instanceof CameraOnStand onStand
                ? onStand.getStand().getId() : -1;
    }

    /** 取景器绘制与后台截图的区块等待共用同一视距上限。 */
    static int effectiveRenderDistance(int serverMaximum) {
        return Math.max(1, Math.min(Minecraft.getInstance().options.renderDistance().get(), serverMaximum));
    }

    /**
     * Render directly into the caller's target, including Exposure's background
     * capture target. A HUD texture would miss that second capture path.
     * <p>
     * 渲染入口。由 Exposure 在取景器/截图流程中回调，而非事件总线。
     *
     * @return true 表示本帧已成功渲染远程场景；false 表示跳过（由调用方走原逻辑）
     */
    public static boolean render(DeltaTracker deltaTracker) {
        // 防重入，避免嵌套正在进行的远程世界渲染。
        if (rendering || WorldRenderInfo.isRendering()) return false;
        CaptureSnapshot snapshot = captureSnapshot();
        if (snapshot == null) return false;
        Minecraft mc = Minecraft.getInstance();
        RemoteSceneStartS2C currentScene = snapshot.scene();
        // 从 IP 的世界加载器取远程维度实例（只查询，不加载/卸载）
        ClientLevel remote = ClientWorldLoader.getWorld(
                ResourceKey.create(Registries.DIMENSION, currentScene.dimension()));
        if (remote == null) return false;
        ClientLevel sourceLevel = snapshot.session().sourceLevel();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        Entity holder = cameraAnchor(snapshot.session());
        Vec3 position = cameraPosition(snapshot, partialTick);
        Camera original = mc.gameRenderer.getMainCamera();
        // 先把主相机设回源世界状态，保存原始位置以便恢复
        original.setup(sourceLevel, holder, false, false, partialTick);
        Vec3 originalPosition = original.getPosition();
        WorldRenderInfo info = new WorldRenderInfo(remote, position,
                effectiveRenderDistance(currentScene.maxRenderDistance()));
        rendering = true;
        // 背景截图需要绑定 Exposure 的渲染目标。
        boolean backgroundCapture = BackgroundScreenshotCaptureTask.isCapturing();
        try {
            if (backgroundCapture) {
                mc.getMainRenderTarget().bindWrite(false);
            }
            // IP skips the vanilla world clear inside WorldRenderInfo. This is
            // a complete view, so stale depth must not hide the new scene.
            // IP 在 WorldRenderInfo 路径下不清屏，这里手动清深度，避免旧深度遮挡新场景
            RenderSystem.depthMask(true);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            // 把 IP 相机状态临时切到远程位置/世界
            ((IECamera) original).ip_resetState(position, remote);
            MyGameRenderer.renderWorldNew(info);
        } finally {
            // 无论成功失败，都要还原所有被改写的状态
            try {
                ((IECamera) original).ip_resetState(originalPosition, sourceLevel);
            } finally {
                rendering = false;
            }
        }
        return true;
    }

    /**
     * 客户端每 tick 的状态机：检测会话有效性、自动开关会话、推进远程粒子。
     * <p>
     * 使用 Post 而非 Pre：Post 在 vanilla 的 use-item 包之后运行，而那个包才是真正
     * 激活相机的。若在 Viewfinder.setup 中发包，会早于 use-item，顺序错误。
     */
    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // 玩家/连接不存在 → 直接关闭（覆盖登出、断线等场景）
        if (mc.player == null || mc.getConnection() == null) {
            close();
            return;
        }
        // Exposure 的直接截图会保持支架镜头数帧，不能被取景器清理逻辑提前重置。
        if (SourceStandCapture.isRenderingSourceScene()) return;
        if (deferredCameraResetTicks > 0) {
            deferredCameraResetTicks--;
        } else {
            clearDetachedStandViewfinder();
        }
        // A physical dimension film has taken ownership of the player transfer.
        // Do not recreate the source-world remote session while IP is applying
        // the position packet, and keep it disabled while the stand camera is
        // still active in the target dimension.
        if (suppressRemoteScene) {
            if (!CameraClient.isActive()) suppressRemoteScene = false;
            tickRemoteParticles(mc);
            return;
        }
        // 仅在相机激活时取当前取景器
        Viewfinder current = CameraClient.isActive() ? CameraClient.viewfinder() : null;
        DimensionFilters.Route mapping = mappingFor(current);
        // 已有会话但任一条件变化 → 关闭（换滤镜、换取景器、跨维度、断线）
        int currentStandId = cameraStandId(current);
        if (session != null && (!session.valid() || session.viewfinder() != current
                || session.cameraStandId() != currentStandId
                || !Objects.equals(session.mapping(), mapping))) {
            close();
        }
        // 无会话但有有效映射 → 开启新会话
        if (session == null && mapping != null) {
            session = new Session(++nextSequence, current, mapping, currentStandId, mc.getConnection(),
                    (ClientLevel) mc.player.level());
            requestRetryTicks = 0;
        }
        if (session != null && scene == null && requestRetryTicks-- <= 0) {
            // Post runs after the vanilla use-item packet activates the camera.
            // Sending inside Viewfinder.setup would precede that packet.
            // 附件同步可能比取景请求晚到，保持目标不变并等待服务端确认。
            PacketDistributor.sendToServer(new CameraSessionRequestC2S(
                    session.sequence(), session.mapping().filter(), session.mapping().dimension(),
                    session.cameraStandId()));
            requestRetryTicks = 10;
        }
        tickRemoteParticles(mc);
    }

    /** 清理服务端已解除操控、跨维度或已脱离 Exposure 状态的支架视角。 */
    private static void clearDetachedStandViewfinder() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 停用的相机仍可能留下取景器；只复位镜头不会解除 Exposure 的界面和输入控制。
        Viewfinder viewfinder = CameraClient.viewfinder();
        if (viewfinder != null && viewfinder.camera() instanceof CameraOnStand onStand
                && onStand.getStand().level() != mc.player.level()) {
            ((CameraOperator) mc.player).removeActiveExposureCamera();
            CameraClient.resetCameraEntity();
            return;
        }

        // Minecraft.cameraEntity is the authoritative render source. It can
        // outlive Exposure's active viewfinder during an IP dimension swap.
        if (mc.getCameraEntity() instanceof CameraStandEntity cameraStand) {
            boolean wrongLevel = cameraStand.level() != mc.player.level();
            boolean active = CameraClient.isActive();
            boolean sameViewfinder = viewfinder != null
                    && viewfinder.camera() instanceof CameraOnStand onStand
                    && onStand.getStand() == cameraStand;
            // The server clears the stand operator as part of a redstone
            // release. That field can briefly be out of sync while Exposure
            // still owns the local viewfinder. Treating it as a stale camera
            // here causes the exact one-frame HUD disappearance seen during
            // close-range redstone shots.
            if (wrongLevel || !active || !sameViewfinder) {
                CameraClient.resetCameraEntity();
            }
        }
    }

    /**
     * 推进远程维度的粒子。
     * <p>
     * IP 渲染远程世界时不会 tick 其粒子，需要手动在“切换世界上下文”中调用，
     * 否则下界岩浆、末地传送门等粒子会静止。
     */
    private static void tickRemoteParticles(Minecraft mc) {
        CaptureSnapshot snapshot = captureSnapshot();
        // 未处于远程渲染或游戏暂停 → 跳过
        if (snapshot == null || mc.isPaused()) return;
        ClientLevel remote = ClientWorldLoader.getWorld(
                ResourceKey.create(Registries.DIMENSION, snapshot.scene().dimension()));
        if (remote == null) return;
        // 远程就是当前世界，无需额外 tick
        if (remote == mc.level) return;
        Vec3 position = cameraPosition(snapshot, 1.0F);
        // withSwitchedWorld 临时把 mc.level 切到远程，使 particleEngine 操作正确的粒子集合
        ClientWorldLoader.withSwitchedWorld(remote, () -> {
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 previous = camera.getPosition();
            try {
                // 临时把相机挪到远程位置，让粒子生成的位置判断正确
                ((IECamera) camera).portal_setPos(position);
                // 每 2 tick 才 animateTick 一次，降低开销；且仅当区块已加载
                if ((mc.player.tickCount & 1) == 0 && remote.hasChunkAt(BlockPos.containing(position))) {
                    remote.animateTick((int) position.x, (int) position.y, (int) position.z);
                }
                mc.particleEngine.tick();
            } finally {
                // 恢复相机位置
                ((IECamera) camera).portal_setPos(previous);
            }
        });
    }

    /**
     * 从取景器上的相机物品解析滤镜映射。返回 null 表示“当前不是远程相机”。
     */
    private static DimensionFilters.Route mappingFor(Viewfinder viewfinder) {
        if (viewfinder == null || viewfinder.camera() == null) return null;
        // 读取相机上安装的滤镜物品
        ItemStack filter = Attachment.FILTER.get(viewfinder.camera().getItemStack()).getForReading();
        // 委托给配置层解析（Exposure 谓词优先，本地 JSON 兜底）
        DimensionFilters.Route entry = DimensionFilters.resolve(
                Minecraft.getInstance().player.registryAccess(), filter,
                Minecraft.getInstance().player.level().dimension().location());
        if (entry != null && Minecraft.getInstance().player.level().dimension().location()
                .equals(entry.dimension())) {
            // Dimension filters are directional. Once the real player is in
            // the declared level, normal Exposure rendering is the view.
            return null;
        }
        return entry;
    }


    /**
     * 玩家登出时清理会话。close() 内部会检查连接一致性，登出时通常不会发包。
     */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        RemoteStandCapture.cancelAll();
        SourceStandCapture.cancelAll();
        close();
        screenshot = null;
        screenshotStand = null;
        suppressRemoteScene = false;
        deferredCameraResetTicks = 0;
    }
}


package com.xfw.shuttershadow.client;

import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.api.event.CameraViewEvent;
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

import java.util.ArrayList;
import java.util.List;

/** 客户端远维度相机入口。 */
@EventBusSubscriber(modid = Shuttershadow.MODID, value = Dist.CLIENT)
public final class ImmersiveCameraClient {

    /** 当前会话；null 表示未开启。所有字段的读写都在客户端主线程进行。 */
    private static Session session;

    /** 服务端确认后的远程场景信息；由 RemoteSceneStartS2C 包填充。 */
    private static RemoteSceneStartS2C scene;
    /** 仅在 Exposure 后台截图期间使用，普通游戏画面仍由取景器会话决定。 */
    private static CaptureSnapshot screenshot;
    private static Entity screenshotStand;

    /** 单调递增的会话序号，用于匹配请求与响应，防止过期包污染新会话。 */
    private static long nextSequence;
    private static int requestRetryTicks;

    /** 渲染防重入标志：IP 的渲染会回调进本类，必须避免递归。 */
    private static boolean rendering;
    /** 维度胶卷传送期间阻止重新打开远维度场景。 */
    private static boolean suppressRemoteScene;
    /** 将相机实体清理推迟到客户端世界切换完成后。 */
    private static int deferredCameraResetTicks;


    /** 一个客户端会话：序号、取景器、路由、支架ID、连接和真实来源ClientLevel。 */
    private record Session(long sequence, Viewfinder viewfinder, DimensionFilters.Route mapping,
                           int cameraStandId, ClientPacketListener connection, ClientLevel sourceLevel) {
        /** 预览要求玩家、同连接及玩家真实level仍为来源level。 */
        boolean valid() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && connection == mc.getConnection() && mc.player.level() == sourceLevel;
        }

        /** 后台截图只要求玩家、同连接和来源level存在，避免短暂Minecraft世界作用域切换使截图误判失效。 */
        boolean validForCapture() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && connection == mc.getConnection() && sourceLevel != null;
        }
    }

    /** 固定会话、场景和相机Y偏移的单帧快照。 */
    private record CaptureSnapshot(Session session, RemoteSceneStartS2C scene, double cameraYOffset) {
        /** 将来源位置减源原点，再按场景比例映射到目标原点并应用Y偏移。 */
        Vec3 targetPosition(Vec3 sourcePosition, double yOffset) {
            return DimensionFilters.mapRelative(sourcePosition.subtract(scene.sourceOrigin()),
                    scene.position(), scene.coordinateScale(), yOffset);
        }
    }

    /** 禁止实例化此工具类。 */
    private ImmersiveCameraClient() {}

    /** 只接受有效当前Session且序号、目标、来源维度完全一致的场景包，过期包直接丢弃。 */
    public static void start(RemoteSceneStartS2C message) {
        // 无会话、会话已失效、或序号不匹配 → 丢弃
        if (session == null || !session.valid() || session.sequence() != message.sequence()
                || !session.mapping().dimension().equals(message.requestedDimension())
                || !session.sourceLevel().dimension().location().equals(message.sourceDimension())) return;
        scene = message;
    }

    /** 负序号取消指定远场/源场后台照片。 */
    public static void stopRemoteScene(RemoteSceneStopS2C message) {
        if (message.captureSequence() < 0) {
            RemoteStandCapture.cancel(message.captureSequence());
            SourceStandCapture.cancel(message.captureSequence());
        } else {
            if (message.captureSequence() > 0
                    && (session == null || session.sequence() != message.captureSequence())) return;
            if (message.closeCamera()) {
                Viewfinder current = CameraClient.viewfinder();
                if (session == null || !session.valid() || session.sequence() != message.captureSequence()
                        || current == null || !CameraViewEvents.sameCamera(session.viewfinder().camera(), current.camera())
                        || !session.mapping().equals(mappingFor(current))) return;
                try {
                    CameraViewEvents.close(CameraViewEvent.CloseReason.EVENT_REQUESTED);
                } finally {
                    try {
                        CameraClient.deactivate();
                    } finally {
                        stopRemoteScene();
                    }
                }
                return;
            }
            stopRemoteScene();
        }
    }

    /** 清会话与场景，暂时抑制重新开远场并延后支架视角复位至少2tick。 */
    public static void stopRemoteScene() {
        session = null;
        scene = null;
        suppressRemoteScene = true;
        // 在 tick 边界清理已脱离源维度的支架相机，不改正在使用的截图相机。
        deferredCameraResetTicks = Math.max(deferredCameraResetTicks, 2);
    }

    /** 仅延迟本地维度支架、截图或已跨维的旧支架复位，普通支架交回Exposure。 */
    public static boolean deferExposureStandCameraReset(Entity cameraEntity) {
        if (!(cameraEntity instanceof CameraStandEntity stand)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getCameraEntity() != stand) return false;
        boolean remoteStand = session != null && session.cameraStandId() == stand.getId()
                && session.sourceLevel() == stand.level();
        if (!remoteStand && screenshotStand != stand && stand.level() == mc.player.level()
                && !SourceStandCapture.isRenderingSourceScene()
                && Attachment.FILTER.get(stand.getCamera()).getForReading()
                        .get(Shuttershadow.DIMENSION_FILTER_TARGET.get()) == null) return false;
        deferredCameraResetTicks = Math.max(deferredCameraResetTicks, 1);
        return true;
    }

    /** 源场截图期间返回null。 */
    private static CaptureSnapshot captureSnapshot() {
        if (SourceStandCapture.isRenderingSourceScene()) return null;
        // 支架截图状态只存在于一次同步离屏绘制内。
        if (screenshot != null && BackgroundScreenshotCaptureTask.isCapturing()
                && screenshot.session().validForCapture()) return screenshot;
        return viewSnapshot();
    }

    /** 只读取玩家当前取景会话，后台照片不会混入观察事件。 */
    private static CaptureSnapshot viewSnapshot() {
        Viewfinder current = CameraClient.isActive() ? CameraClient.viewfinder() : null;
        // 同一相机同步重建取景器后仍使用已确认的场景，真实换相机或路由才停止预览。
        if (scene == null || session == null || !session.valid() || current == null
                || !CameraViewEvents.sameCamera(session.viewfinder().camera(), current.camera())
                || !session.mapping().equals(mappingFor(current))) return null;
        // 从相机物品读取 Y 偏移；无则 0
        double offset = current.camera()
                .map((item, stack) -> item.getYPositionOffset(stack)).orElse(0.0D);
        return new CaptureSnapshot(session, scene, offset);
    }

    /** 为公开拍摄场景建立任意真实持有者的临时镜头，不打开用户取景会话。 */
    static void beginScreenshot(RemoteSceneStartS2C scene, Entity holder, ItemStack camera) {
        Minecraft mc = Minecraft.getInstance();
        double offset = camera.getItem() instanceof CameraItem item ? item.getYPositionOffset(camera) : 0.0D;
        screenshot = new CaptureSnapshot(new Session(scene.sequence(), null, null,
                holder instanceof CameraStandEntity stand ? stand.getId() : -1,
                mc.getConnection(), (ClientLevel) holder.level()), scene, offset);
        screenshotStand = holder;
    }

    /** 只清除序号匹配的临时目标截图。 */
    static void endScreenshot(long sequence) {
        if (screenshot != null && screenshot.scene().sequence() == sequence) {
            screenshot = null;
            screenshotStand = null;
        }
    }

    /** 返回是否存在手动支架截图快照。 */
    public static boolean isStandScreenshot() {
        return screenshot != null;
    }

    /** 同连接时发送当前预览关闭包，然后清Session、scene和重试计数。 */
    private static void close() {
        // 仅当连接未变时才发包；断线/重连时连接已失效，发了也是错的
        if (session != null && session.connection() == Minecraft.getInstance().getConnection()) {
            PacketDistributor.sendToServer(new CameraSessionCloseC2S(session.sequence()));
        }
        session = null;
        scene = null;
        requestRetryTicks = 0;
        // 待完成的截图或传送仍可能使用远维度世界。
    }

    /** 把源锚点插值眼位映射到目标场景并应用相机Y偏移。 */
    private static Vec3 cameraPosition(CaptureSnapshot snapshot, float partialTick) {
        return snapshot.targetPosition(cameraAnchor(snapshot.session()).getEyePosition(partialTick),
                snapshot.cameraYOffset());
    }

    /** 只在匹配的目标支架渲染堆栈内枚举真实来源玩家，按服务端玩家半径筛选并插值，再生成目标投影位置/相机位置记录。 */
    static List<PlayerProjection> playerProjections(ClientLevel remote, Camera remoteCamera, float partialTick) {
        if (!rendering) return List.of();
        CaptureSnapshot snapshot = captureSnapshot();
        // 手持自拍仍由 Exposure 原生玩家视角与自拍流程处理。
        if (snapshot == null || snapshot.session().cameraStandId() < 0) return List.of();
        ClientLevel source = snapshot.session().sourceLevel();
        if (source == null) return List.of();

        // 实体钩子可能在进入世界或渲染上下文出栈后调用，
        // 读取渲染栈顶前必须确认栈不为空。
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

    /** 源玩家实体、目标level、投影脚底位置和目标相机位置的渲染记录。 */
    static record PlayerProjection(AbstractClientPlayer player, ClientLevel level, Vec3 position,
                                   Vec3 cameraPosition) {}

    /** 后台截图用其支架。 */
    private static Entity cameraAnchor(Session session) {
        if (screenshot != null && screenshot.session() == session) return screenshotStand;
        Minecraft mc = Minecraft.getInstance();
        if (session.cameraStandId() >= 0 && session.sourceLevel() != null) {
            Entity stand = session.sourceLevel().getEntity(session.cameraStandId());
            if (stand != null) return stand;
        }
        return mc.getCameraEntity() == null ? mc.player : mc.getCameraEntity();
    }

    /** CameraOnStand返回支架ID，其余返回-1。 */
    private static int cameraStandId(Viewfinder viewfinder) {
        if (viewfinder == null || viewfinder.camera() == null) return -1;
        return viewfinder.camera() instanceof CameraOnStand onStand
                ? onStand.getStand().getId() : -1;
    }

    /** 返回客户端图形视距与服务端相机上限的最小值，至少1区块。 */
    static int effectiveRenderDistance(int serverMaximum) {
        return Math.max(1, Math.min(Minecraft.getInstance().options.renderDistance().get(), serverMaximum));
    }

    /** 拒绝嵌套远场。 */
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
            // 远维度绘制跳过原版清屏，本次绘制覆盖完整视野，
            // 需清除旧深度，避免遮住新场景。
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

    /** 无连接时关闭。 */
    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // 玩家/连接不存在 → 直接关闭（覆盖登出、断线等场景）
        if (mc.player == null || mc.getConnection() == null) {
            try {
                CameraViewEvents.close(CameraViewEvent.CloseReason.DISCONNECTED);
            } finally {
                close();
            }
            return;
        }
        // Exposure 的直接截图会保持支架镜头数帧，不能被取景器清理逻辑提前重置。
        if (SourceStandCapture.isRenderingSourceScene()) return;
        CaptureSnapshot preview = viewSnapshot();
        CameraViewEvents.tick(preview == null ? null : preview.scene().dimension(),
                preview == null ? null : preview.targetPosition(
                        cameraAnchor(preview.session()).position(), 0.0D));
        if (deferredCameraResetTicks > 0) {
            deferredCameraResetTicks--;
        } else {
            clearDetachedStandViewfinder();
        }
        // 维度胶卷已接管玩家传送。
        // 应用位置数据包时不重建来源世界的远景会话，
        // 目标维度中仍在控制支架期间
        // 也保持该会话关闭。
        if (suppressRemoteScene) {
            if (!CameraClient.isActive()) suppressRemoteScene = false;
            tickRemoteParticles(mc);
            return;
        }
        // 仅在相机激活时取当前取景器
        Viewfinder current = CameraClient.isActive() ? CameraClient.viewfinder() : null;
        DimensionFilters.Route mapping = mappingFor(current);
        // 真正换相机、换滤镜、跨维度或断线时关闭；同相机同步仅更新取景器。
        int currentStandId = cameraStandId(current);
        if (session != null && (!session.valid() || current == null
                || !CameraViewEvents.sameCamera(session.viewfinder().camera(), current.camera())
                || session.cameraStandId() != currentStandId
                || !session.mapping().equals(mapping))) {
            close();
        } else if (session != null && session.viewfinder() != current) {
            session = new Session(session.sequence(), current, session.mapping(), session.cameraStandId(),
                    session.connection(), session.sourceLevel());
        }
        // 无会话但有有效映射 → 开启新会话
        if (session == null && mapping != null) {
            session = new Session(++nextSequence, current, mapping, currentStandId, mc.getConnection(),
                    (ClientLevel) mc.player.level());
            requestRetryTicks = 0;
        }
        if (session != null && scene == null && requestRetryTicks-- <= 0) {
            // 后置事件在原版使用物品数据包激活相机后执行。
            // 若在取景器初始化时发送请求，会早于相机激活数据包。
            // 附件同步可能比取景请求晚到，保持目标不变并等待服务端确认。
            PacketDistributor.sendToServer(new CameraSessionRequestC2S(
                    session.sequence(), session.mapping().filter(), session.mapping().dimension(),
                    session.cameraStandId()));
            requestRetryTicks = 10;
        }
        tickRemoteParticles(mc);
    }

    /** 发现操作者仍关联其他世界支架时移除活动相机并重设玩家视角。 */
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

        // 实际相机实体决定渲染来源，
        // 在无缝切换世界期间可能比取景器保持得更久。
        if (mc.getCameraEntity() instanceof CameraStandEntity cameraStand) {
            boolean wrongLevel = cameraStand.level() != mc.player.level();
            boolean active = CameraClient.isActive();
            boolean sameViewfinder = viewfinder != null
                    && viewfinder.camera() instanceof CameraOnStand onStand
                    && onStand.getStand() == cameraStand;
            // 红石释放快门时服务端会清除支架操作者，
            // 该字段可能短暂不同步，
            // 而本地仍在使用取景器。此时若判定相机失效，
            // 会在近距离红石拍照时
            // 导致界面短暂消失一帧。
            if (wrongLevel || !active || !sameViewfinder) {
                CameraClient.resetCameraEntity();
            }
        }
    }

    /** 未暂停且有目标场景时临时切换目标世界与camera position，在已加载镜头区块每两tick animateTick，随后tick目标粒子。 */
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

    /** 从当前viewfinder滤镜解析来源路由，目标等于玩家真实维度则无远场。 */
    private static DimensionFilters.Route mappingFor(Viewfinder viewfinder) {
        if (viewfinder == null || viewfinder.camera() == null) return null;
        // 读取相机上安装的滤镜物品
        ItemStack filter = Attachment.FILTER.get(viewfinder.camera().getItemStack()).getForReading();
        // 按滤镜组件解析目标维度。
        return DimensionFilters.resolve(
                filter, Minecraft.getInstance().player.level().dimension().location());
    }


    /** 登出取消全部后台照片并清预览、临时截图、抑制及延后复位状态。 */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        try {
            CameraViewEvents.close(CameraViewEvent.CloseReason.DISCONNECTED);
        } finally {
            RemoteStandCapture.cancelAll();
            SourceStandCapture.cancelAll();
            close();
            screenshot = null;
            screenshotStand = null;
            suppressRemoteScene = false;
            deferredCameraResetTicks = 0;
        }
    }
}


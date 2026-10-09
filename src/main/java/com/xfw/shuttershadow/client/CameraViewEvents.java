package com.xfw.shuttershadow.client;

import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.api.event.CameraViewEvent;
import com.xfw.shuttershadow.camera.CameraEnchantments;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder;
import io.github.mortuusars.exposure.world.camera.Camera;
import io.github.mortuusars.exposure.world.camera.CameraInHand;
import io.github.mortuusars.exposure.world.camera.CameraOnStand;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;

/** 将 Exposure 取景器生命周期转为客户端公开事件，并安全同步自拍模式。 */
@EventBusSubscriber(modid = Shuttershadow.MODID, value = Dist.CLIENT)
public final class CameraViewEvents {
    private static Active active;
    private static long nextSession;
    private static boolean rebinding;
    private static boolean replacing;

    /** 保存逻辑相机身份，不因重复同步重建取景器而重复开关。 */
    private static final class Active {
        private final long id = ++nextSession;
        private final LocalPlayer player = Minecraft.getInstance().player;
        private final ClientPacketListener connection = Minecraft.getInstance().getConnection();
        private final Camera camera;
        private Viewfinder viewfinder;
        private CameraViewEvent.Context context;
        private CameraViewEvent.Mode pendingMode;

        /** 保存相机身份和当前取景器。 */
        private Active(Camera camera, Viewfinder viewfinder) {
            this.camera = camera;
            this.viewfinder = viewfinder;
        }

        /** 比较同一玩家、连接、相机编号和手持或支架位置。 */
        private boolean matches(Camera next) {
            Minecraft mc = Minecraft.getInstance();
            return player == mc.player && connection == mc.getConnection()
                    && sameCamera(camera, next);
        }
    }

    /** 禁止实例化此协调器。 */
    private CameraViewEvents() {}

    /** 按相机编号和手或支架位置识别同一相机，允许同步重建取景器。 */
    static boolean sameCamera(Camera camera, Camera next) {
        return camera != null && next != null && camera.getId().equals(next.getId())
                && (camera instanceof CameraInHand hand && next instanceof CameraInHand nextHand
                && hand.getHand() == nextHand.getHand()
                || camera instanceof CameraOnStand stand && next instanceof CameraOnStand nextStand
                && stand.getStand() == nextStand.getStand());
    }

    /** 在 Exposure 重建前标记同一逻辑相机的重绑定。 */
    public static void beginSetup(Camera camera) {
        rebinding = active != null && active.matches(camera);
        replacing = active != null && !rebinding;
    }

    /** 原生 setup 已保存旧视角后发布打开事件并应用指定模式。 */
    public static void finishSetup(Viewfinder viewfinder) {
        try {
            if (viewfinder == null || Minecraft.getInstance().player == null) {
                close(CameraViewEvent.CloseReason.INVALIDATED);
                return;
            }
            if (rebinding && active != null) {
                active.viewfinder = viewfinder;
                if (active.context.mode() == CameraViewEvent.Mode.SELFIE
                        || Minecraft.getInstance().options.getCameraType() == CameraType.THIRD_PERSON_FRONT) {
                    applyMode(active.context.mode());
                }
                return;
            }
            Active created = new Active(viewfinder.camera(), viewfinder);
            active = created;
            CameraViewEvent.Open event = new CameraViewEvent.Open(snapshot(created, null, null));
            CameraViewEvent.Mode originalMode = Minecraft.getInstance().options.getCameraType() == CameraType.THIRD_PERSON_FRONT
                    && event.getContext().supportsSelfie()
                    ? CameraViewEvent.Mode.SELFIE : CameraViewEvent.Mode.NORMAL;
            event.trySetMode(originalMode);
            NeoForge.EVENT_BUS.post(event);
            if (active != created) return;
            created.context = event.getContext();
            if (created.context.mode() != originalMode) {
                created.pendingMode = created.context.mode();
                applyMode(created.pendingMode);
            }
        } finally {
            rebinding = false;
            replacing = false;
        }
    }

    /** 原生取景器移除后结束逻辑会话；重复同步只替换取景器。 */
    public static void removed() {
        if (rebinding) return;
        Minecraft mc = Minecraft.getInstance();
        CameraViewEvent.CloseReason reason = replacing ? CameraViewEvent.CloseReason.CAMERA_CHANGED
                : active != null && (active.player != mc.player || active.connection != mc.getConnection())
                ? CameraViewEvent.CloseReason.DISCONNECTED
                : active != null && !active.player.isAlive() ? CameraViewEvent.CloseReason.DEATH
                : active != null && active.context != null
                && !active.context.sourceDimension().equals(active.player.level().dimension().location())
                ? CameraViewEvent.CloseReason.DIMENSION_CHANGED : CameraViewEvent.CloseReason.NORMAL;
        close(reason);
    }

    /** 发布一次结束通知，不改变 Exposure 的清理和发送流程。 */
    public static void close(CameraViewEvent.CloseReason reason) {
        Active previous = active;
        active = null;
        if (previous != null && previous.context != null) {
            NeoForge.EVENT_BUS.post(new CameraViewEvent.Close(previous.context, reason));
        }
    }

    /** 先同步已激活相机的模式，再发布一次真实取景 Tick。 */
    public static void tick(ResourceLocation targetDimension, Vec3 targetPosition) {
        Active current = active;
        if (current == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (current.player != mc.player || current.connection != mc.getConnection()) {
            close(CameraViewEvent.CloseReason.DISCONNECTED);
            return;
        }
        if (!current.player.isAlive()) {
            try {
                close(CameraViewEvent.CloseReason.DEATH);
            } finally {
                ((CameraOperator) current.player).removeActiveExposureCamera();
            }
            return;
        }
        if (current.viewfinder != CameraClient.viewfinder() || !CameraClient.isActive()) {
            close(CameraViewEvent.CloseReason.INVALIDATED);
            return;
        }
        if (current.viewfinder.camera() instanceof CameraOnStand stand
                && stand.getStand().level() != current.player.level()) {
            close(CameraViewEvent.CloseReason.DIMENSION_CHANGED);
            return;
        }
        if (current.pendingMode != null) {
            applyMode(current.pendingMode);
            current.viewfinder.selfie().updateSelfieMode();
            current.pendingMode = null;
        }
        if (mc.isPaused()) return;
        current.context = snapshot(current, targetDimension, targetPosition);
        CameraViewEvent.Tick event = new CameraViewEvent.Tick(current.context);
        NeoForge.EVENT_BUS.post(event);
        if (active != current) return;
        if (event.isCloseRequested()) {
            try {
                close(CameraViewEvent.CloseReason.EVENT_REQUESTED);
            } finally {
                CameraClient.deactivate();
            }
        } else if (event.getContext().mode() != current.context.mode()) {
            applyMode(event.getContext().mode());
            current.viewfinder.selfie().updateSelfieMode();
            current.context = event.getContext();
        }
    }

    /** 客户端附魔监听先提供默认自拍，其他监听者仍可覆盖。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void defaultMode(CameraViewEvent.Open event) {
        CameraViewEvent.Context context = event.getContext();
        if (context.side() == CameraViewEvent.Side.CLIENT && context.supportsSelfie()
                && CameraEnchantments.has(context.camera(), CameraEnchantments.NARCISSISM)) {
            event.trySetMode(CameraViewEvent.Mode.SELFIE);
        }
    }

    /** 从真实取景器读取来源；目标只采用服务端确认的场景。 */
    private static CameraViewEvent.Context snapshot(Active current, ResourceLocation targetDimension,
                                                     Vec3 targetPosition) {
        Camera camera = current.viewfinder.camera();
        int standId = camera instanceof CameraOnStand stand ? stand.getStand().getId() : -1;
        var holder = camera.getHolder().asHolderEntity();
        return new CameraViewEvent.Context(current.id, CameraViewEvent.Side.CLIENT, current.player,
                camera.getItemStack(), standId, holder.level().dimension().location(), holder.position(),
                targetDimension, targetPosition, standId < 0 && camera.inSelfieMode()
                ? CameraViewEvent.Mode.SELFIE : CameraViewEvent.Mode.NORMAL);
    }

    /** 只改变取景视角；设置同步延至激活操作发包之后。 */
    private static void applyMode(CameraViewEvent.Mode mode) {
        Minecraft.getInstance().options.setCameraType(mode == CameraViewEvent.Mode.SELFIE
                ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON);
    }
}

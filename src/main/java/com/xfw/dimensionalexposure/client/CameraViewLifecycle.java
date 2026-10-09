package com.xfw.dimensionalexposure.client;

import com.xfw.dimensionalexposure.camera.CameraEnchantments;
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

/** 内部同步取景器生命周期、默认自拍和同相机重绑定后的自拍模式。 */
public final class CameraViewLifecycle {
    private static Active active;
    private static boolean rebinding;

    /** 保存逻辑相机身份，不因重复同步重建取景器而丢失自拍模式。 */
    private static final class Active {
        private final LocalPlayer player = Minecraft.getInstance().player;
        private final ClientPacketListener connection = Minecraft.getInstance().getConnection();
        private final Camera camera;
        private Viewfinder viewfinder;
        private Boolean pendingSelfie;

        private Active(Camera camera, Viewfinder viewfinder) {
            this.camera = camera;
            this.viewfinder = viewfinder;
        }

        /** 比较同一玩家、连接、相机编号和手持或支架位置。 */
        private boolean matches(Camera next) {
            Minecraft mc = Minecraft.getInstance();
            return player == mc.player && connection == mc.getConnection() && sameCamera(camera, next);
        }
    }

    private CameraViewLifecycle() {}

    /** 按相机编号和手或支架位置识别同一相机，允许同步重建取景器。 */
    static boolean sameCamera(Camera camera, Camera next) {
        return camera != null && next != null && camera.getId().equals(next.getId())
                && (camera instanceof CameraInHand hand && next instanceof CameraInHand nextHand
                && hand.getHand() == nextHand.getHand()
                || camera instanceof CameraOnStand stand && next instanceof CameraOnStand nextStand
                && stand.getStand() == nextStand.getStand());
    }

    /** 重绑定前保存当前模式，防止原生关闭旧取景器时还原视角并丢失选择。 */
    public static void beginSetup(Camera camera) {
        rebinding = active != null && active.matches(camera);
        if (rebinding && active.pendingSelfie == null) {
            active.pendingSelfie = camera instanceof CameraInHand
                    && Minecraft.getInstance().options.getCameraType() == CameraType.THIRD_PERSON_FRONT;
        }
    }

    /** 原生 setup 已保存旧视角后应用默认自拍，同相机同步则恢复已有模式。 */
    public static void finishSetup(Viewfinder viewfinder) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (viewfinder == null || mc.player == null) {
                close();
                return;
            }
            if (rebinding && active != null) {
                active.viewfinder = viewfinder;
                if (active.pendingSelfie != null) applyMode(active.pendingSelfie);
                return;
            }
            Active created = new Active(viewfinder.camera(), viewfinder);
            active = created;
            if (defaultSelfie(created.camera)
                    && mc.options.getCameraType() != CameraType.THIRD_PERSON_FRONT) {
                created.pendingSelfie = true;
                applyMode(true);
            }
        } finally {
            rebinding = false;
        }
    }

    /** 仅手持自恋附魔相机在真正打开时默认进入自拍。 */
    private static boolean defaultSelfie(Camera camera) {
        return camera instanceof CameraInHand
                && CameraEnchantments.has(camera.getItemStack(), CameraEnchantments.NARCISSISM);
    }

    /** 真实换维后结束来源支架观察，手持不重新应用附魔默认自拍。 */
    public static void dimensionChanged() {
        Active current = active;
        if (current == null || current.player != Minecraft.getInstance().player) return;
        if (current.viewfinder.camera() instanceof CameraOnStand stand
                && stand.getStand().level() != current.player.level()) close();
    }

    /** 原生取景器移除后结束逻辑会话；重复同步只替换取景器。 */
    public static void removed() {
        if (!rebinding) close();
    }

    /** 清除内部逻辑会话，Exposure 继续负责取景器和活动物品清理。 */
    public static void close() {
        active = null;
    }

    /** 先验证真实玩家及取景器，再同步激活发包之后的自拍设置。 */
    public static void tick() {
        Active current = active;
        if (current == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (current.player != mc.player || current.connection != mc.getConnection()) {
            close();
            return;
        }
        if (!current.player.isAlive()) {
            close();
            ((CameraOperator) current.player).removeActiveExposureCamera();
            return;
        }
        if (current.viewfinder != CameraClient.viewfinder() || !CameraClient.isActive()) {
            close();
            return;
        }
        if (current.viewfinder.camera() instanceof CameraOnStand stand
                && stand.getStand().level() != current.player.level()) {
            close();
            return;
        }
        if (current.pendingSelfie != null) {
            applyMode(current.pendingSelfie);
            current.viewfinder.selfie().updateSelfieMode();
            current.pendingSelfie = null;
        }
    }

    /** 只改变取景视角；设置同步延至激活操作发包之后。 */
    private static void applyMode(boolean selfie) {
        Minecraft.getInstance().options.setCameraType(selfie
                ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON);
    }
}

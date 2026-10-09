package com.xfw.dimensionalexposure.network;

import com.xfw.dimensionalexposure.camera.DimensionFilmCapture;
import com.xfw.dimensionalexposure.camera.CameraCaptureTransactions;
import com.xfw.dimensionalexposure.DimensionalExposure;
import com.xfw.dimensionalexposure.client.ImmersiveCameraClient;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 相机业务payload注册及客户端隔离调用。 */
@EventBusSubscriber(modid = DimensionalExposure.MODID)
public final class DimensionalExposureNetwork {
    public static final String PROTOCOL_VERSION = "19";
    /** 禁止实例化此工具类。 */
    private DimensionalExposureNetwork() {}

    /** 注册相机观察、图片结果与个人传送接受选项的双向业务包。 */
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // NeoForge 默认在主线程执行处理器，业务代码无需再次排队。
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(CameraCaptureFailedC2S.TYPE, CameraCaptureFailedC2S.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player)
                        CameraCaptureTransactions.imageFailed(player, payload.exposureId());
                });
        registrar.playToServer(CameraSessionRequestC2S.TYPE, CameraSessionRequestC2S.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        RemoteCameraSession.handle(payload, player);
                    }
                });
        registrar.playToServer(CameraSessionCloseC2S.TYPE, CameraSessionCloseC2S.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        try {
                            RemoteStandPreparation.onCaptureFinished(player, payload.sequence(), payload.captured());
                        } finally {
                            RemoteCameraSession.close(player, payload.sequence());
                        }
                    }
                });
        registrar.playToServer(CameraTeleportPreferenceC2S.TYPE, CameraTeleportPreferenceC2S.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        DimensionFilmCapture.setTeleportPreferences(player, payload.acceptStand(), payload.acceptOtherPlayers());
                    }
                });
        // 外层回调执行时才加载客户端处理类，双方仍登记相同的包类型和编码器。
        registrar.playToClient(RemoteSceneStartS2C.TYPE, RemoteSceneStartS2C.STREAM_CODEC,
                (payload, context) -> ClientHandlers.startScene(payload));
        registrar.playToClient(RemoteSceneStopS2C.TYPE, RemoteSceneStopS2C.STREAM_CODEC,
                (payload, context) -> ClientHandlers.stopScene(payload));
    }

    /** 仅在客户端收到业务包后加载，以明确类型调用观察和自拍处理器。 */
    private static final class ClientHandlers {
        /** 禁止创建客户端包处理器实例。 */
        private ClientHandlers() {}

        /** 启用服务端公布的目标观察场景。 */
        private static void startScene(RemoteSceneStartS2C payload) {
            ImmersiveCameraClient.start(payload);
        }

        /** 停止指定观察或拍摄场景。 */
        private static void stopScene(RemoteSceneStopS2C payload) {
            ImmersiveCameraClient.stopRemoteScene(payload);
        }
    }
}



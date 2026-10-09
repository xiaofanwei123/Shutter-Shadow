package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.camera.DimensionFilmCapture;
import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.client.DimensionFilmClient;
import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 相机业务payload注册及客户端隔离调用。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class ShuttershadowNetwork {
    public static final String PROTOCOL_VERSION = "16";
    /** 禁止实例化此工具类。 */
    private ShuttershadowNetwork() {}

    /** 注册相机观察、传送确认和图片结果的双向业务包。 */
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // NeoForge 默认在主线程执行处理器，业务代码无需再次排队。
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(CameraCaptureFailedC2S.TYPE, CameraCaptureFailedC2S.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player)
                        com.xfw.shuttershadow.camera.CameraCaptureEvents.imageFailed(player, payload.exposureId());
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
                            try {
                                com.xfw.shuttershadow.camera.CameraCaptureEvents.captureFinished(player, payload.sequence(), payload.captured());
                            } finally {
                                RemoteCameraSession.close(player, payload.sequence());
                            }
                        }
                    }
                });
        registrar.playToServer(DimensionFilmReadyC2S.TYPE, DimensionFilmReadyC2S.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        DimensionFilmCapture.clientReady(player, payload.transaction());
                    }
                });
        registrar.playToServer(StandTeleportPreferenceC2S.TYPE, StandTeleportPreferenceC2S.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        DimensionFilmCapture.setStandTeleportPreference(player, payload.accepted());
                    }
                });
        // 外层回调执行时才加载客户端处理类，双方仍登记相同的包类型和编码器。
        registrar.playToClient(RemoteSceneStartS2C.TYPE, RemoteSceneStartS2C.STREAM_CODEC,
                (payload, context) -> ClientHandlers.startScene(payload));
        registrar.playToClient(RemoteSceneStopS2C.TYPE, RemoteSceneStopS2C.STREAM_CODEC,
                (payload, context) -> ClientHandlers.stopScene(payload));
        registrar.playToClient(DimensionFilmStartS2C.TYPE, DimensionFilmStartS2C.STREAM_CODEC,
                (payload, context) -> ClientHandlers.startFilmCapture(payload));
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

        /** 在自拍传送后等待客户端就绪并继续拍摄。 */
        private static void startFilmCapture(DimensionFilmStartS2C payload) {
            DimensionFilmClient.start(payload);
        }
    }
}



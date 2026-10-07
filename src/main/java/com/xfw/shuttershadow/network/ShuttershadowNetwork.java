package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.camera.DimensionFilmCapture;
import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.Arrays;

/** 相机业务payload注册及客户端隔离调用。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class ShuttershadowNetwork {
    public static final String PROTOCOL_VERSION = "14";
    /** 禁止实例化此工具类。 */
    private ShuttershadowNetwork() {}

    /** 注册四个C2S与三个S2C相机业务包。 */
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(CameraSessionRequestC2S.TYPE, CameraSessionRequestC2S.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        RemoteCameraSession.handle(payload, player);
                    }
                }));
        registrar.playToServer(CameraSessionCloseC2S.TYPE, CameraSessionCloseC2S.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        RemoteStandPreparation.onCaptureFinished(player, payload.sequence(), payload.captured());
                        RemoteCameraSession.close(player, payload.sequence());
                    }
                }));
        registrar.playToServer(DimensionFilmReadyC2S.TYPE, DimensionFilmReadyC2S.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        DimensionFilmCapture.clientReady(player, payload.transaction());
                    }
                }));
        registrar.playToServer(StandTeleportPreferenceC2S.TYPE, StandTeleportPreferenceC2S.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        DimensionFilmCapture.setStandTeleportPreference(player, payload.accepted());
                    }
                }));
        // 仅客户端调用时解析方法体，服务端注册时不会加载客户端类型。
        registrar.playToClient(RemoteSceneStartS2C.TYPE, RemoteSceneStartS2C.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        dispatchOnClient("ImmersiveCameraClient", "start", payload)));
        registrar.playToClient(RemoteSceneStopS2C.TYPE, RemoteSceneStopS2C.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        dispatchOnClient("ImmersiveCameraClient", "stopRemoteScene", payload)));
        registrar.playToClient(DimensionFilmStartS2C.TYPE, DimensionFilmStartS2C.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        dispatchOnClient("DimensionFilmClient", "start", payload)));
    }

    /** 按客户端类名和真实参数类型反射调用静态处理器，专用服务端不加载客户端类型。 */
    private static void dispatchOnClient(String handlerClass, String method, Object... arguments) {
        try {
            Class<?> handler = Class.forName("com.xfw.shuttershadow.client." + handlerClass);
            Class<?>[] parameterTypes = Arrays.stream(arguments)
                    .map(Object::getClass).toArray(Class<?>[]::new);
            handler.getMethod(method, parameterTypes).invoke(null, arguments);
        } catch (ClassNotFoundException ignored) {
            // 专用服务端不会调用此回调。
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to invoke client handler " + handlerClass + "." + method,
                    exception);
        }
    }
}



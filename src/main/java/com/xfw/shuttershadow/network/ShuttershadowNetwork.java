package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.DimensionFilmCapture;
import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.Arrays;

/** 相机取景、截图提交和胶卷传送的专用消息注册。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class ShuttershadowNetwork {
    public static final String PROTOCOL_VERSION = "12";
    private ShuttershadowNetwork() {}

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
        // This body is resolved when invoked on the client, not on server registration.
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

    /**
     * Keeps the common network registrar free of a hard client class reference.
     * A dedicated server still registers the payload type, but can never load the
     * client renderer when the client-only handler is absent.
     */
    private static void dispatchOnClient(String handlerClass, String method, Object... arguments) {
        try {
            Class<?> handler = Class.forName("com.xfw.shuttershadow.client." + handlerClass);
            Class<?>[] parameterTypes = Arrays.stream(arguments)
                    .map(Object::getClass).toArray(Class<?>[]::new);
            handler.getMethod(method, parameterTypes).invoke(null, arguments);
        } catch (ClassNotFoundException ignored) {
            // The callback is never invoked on a dedicated server.
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to invoke client handler " + handlerClass + "." + method,
                    exception);
        }
    }
}



package com.xfw.shuttershadow.network;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.xfw.shuttershadow.network.CoreNetworkHandshake;
import com.xfw.shuttershadow.network.PacketRedirection;

// 共用维度配置、维度类型同步、相机区块回执与远程世界包路由注册。
public class CorePayloads {
    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(ShuttershadowNetwork.PROTOCOL_VERSION);

        // Configuration
        registrar.configurationToClient(CoreNetworkHandshake.S2CConfigStartPacket.TYPE,
                CoreNetworkHandshake.S2CConfigStartPacket.CODEC,
                CoreNetworkHandshake.S2CConfigStartPacket::handle);

        registrar.configurationToServer(CoreNetworkHandshake.C2SConfigCompletePacket.TYPE,
                CoreNetworkHandshake.C2SConfigCompletePacket.CODEC,
                CoreNetworkHandshake.C2SConfigCompletePacket::handle);

        // Play
        registrar.playToClient(PacketRedirection.Payload.TYPE, PacketRedirection.Payload.CODEC, (p, c) -> p.handle((ClientGamePacketListener) c.listener()));
        registrar.playToClient(MiscNetworking.DimIdSyncPacket.TYPE, MiscNetworking.DimIdSyncPacket.CODEC,
                (payload, context) -> payload.handleOnNetworkingThread());
        registrar.playToServer(RemoteChunkBatchReceivedC2S.TYPE, RemoteChunkBatchReceivedC2S.STREAM_CODEC,
                RemoteChunkBatchReceivedC2S::handle);
    }
}

package com.xfw.dimensionalexposure.network;


import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

// 共用连接就绪、维度类型同步、相机区块回执与远程世界包路由注册。
/** 内核数据包注册入口，使用与相机包同一PayloadRegistrar版本。 */
public class CorePayloads {
    /** 注册两个空就绪包及三个游戏包，兼容性共用 NeoForge 协议标识。 */
    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(DimensionalExposureNetwork.PROTOCOL_VERSION);

        // 配置阶段只确认早期连接准备，不传送或比较独立内核版本。
        registrar.configurationToClient(CoreNetworkHandshake.ReadyS2C.TYPE,
                CoreNetworkHandshake.ReadyS2C.CODEC,
                CoreNetworkHandshake.ReadyS2C::handle);

        registrar.configurationToServer(CoreNetworkHandshake.ReadyC2S.TYPE,
                CoreNetworkHandshake.ReadyC2S.CODEC,
                CoreNetworkHandshake.ReadyC2S::handle);

        // 游戏阶段的数据包。
        registrar.playToClient(PacketRedirection.Payload.TYPE, PacketRedirection.Payload.CODEC, (p, c) -> p.handle((ClientGamePacketListener) c.listener()));
        registrar.playToClient(MiscNetworking.DimIdSyncPacket.TYPE, MiscNetworking.DimIdSyncPacket.CODEC,
                (payload, context) -> payload.handleOnNetworkingThread());
        registrar.playToServer(RemoteChunkBatchReceivedC2S.TYPE, RemoteChunkBatchReceivedC2S.STREAM_CODEC,
                RemoteChunkBatchReceivedC2S::handle);
    }
}

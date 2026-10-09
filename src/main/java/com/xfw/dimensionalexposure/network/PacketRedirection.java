package com.xfw.dimensionalexposure.network;


import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.xfw.dimensionalexposure.DimensionalExposure;
import com.xfw.dimensionalexposure.access.IEWorld;

import java.util.ArrayList;
import java.util.List;

/** 给原版客户端游戏包附加维度，让同一连接同步多个世界。 */
public class PacketRedirection {
    // 次级维度的数据包使用本模组的重定向通道。
    public static final ResourceLocation payloadId =
        ResourceLocation.parse("dimensional_exposure:redirect");
    
    private static final ThreadLocal<ResourceKey<Level>> serverPacketRedirection =
        ThreadLocal.withInitial(() -> null);

    /** 校验世界线程，在临时重定向维度中执行并恢复旧值。 */
    public static void withForceRedirect(ServerLevel world, Runnable func) {
        if (((IEWorld) world).portal_getThread() != Thread.currentThread()) {
            DimensionalExposure.LOGGER.error(
                "It's possible that a mod is trying to handle packet in networking thread instead of server thread. This is not thread safe and can cause rare bugs! (DimensionalExposure is checking the packet handling thread)",
                new Throwable()
            );
        }
        
        ResourceKey<Level> redirectDim = world.dimension();
        
        ResourceKey<Level> oldRedirection = serverPacketRedirection.get();
        
        if (oldRedirection != redirectDim) {
            serverPacketRedirection.set(redirectDim);
        }
        
        try {
            func.run();
        }
        finally {
            if (oldRedirection != redirectDim) {
                serverPacketRedirection.set(oldRedirection);
            }
        }
    }
    
    /** 取得当前线程强制重定向维度。 */
    @Nullable
    public static ResourceKey<Level> getForceRedirectDimension() {
        return serverPacketRedirection.get();
    }
    
    // 避免重复嵌套维度重定向。
    /** 包属于玩家真实维度或已处于同一强制作用域时直接发。 */
    public static void sendRedirectedPacket(
        ServerGamePacketListenerImpl serverPlayNetworkHandler,
        Packet<ClientGamePacketListener> packet,
        ResourceKey<Level> dimension
    ) {
        if (serverPlayNetworkHandler.player.level().dimension() == dimension
                || getForceRedirectDimension() == dimension) {
            serverPlayNetworkHandler.send(packet);
        }
        else {
            serverPlayNetworkHandler.send(
                createRedirectedMessage(
                    dimension,
                    packet
                )
            );
        }
    }
    
    /** 已重定向包直接复用。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Packet<ClientGamePacketListener> createRedirectedMessage(
        ResourceKey<Level> dimension,
        Packet<ClientGamePacketListener> packet
    ) {
        if (isRedirectPacket(packet)) {
            // 避免重复嵌套维度重定向。
            return packet;
        }
        
        Validate.isTrue(!(packet instanceof BundleDelimiterPacket));
        if (packet instanceof ClientboundBundlePacket bundlePacket) {
            // 原版会专门处理捆绑数据包，
            // 不应将整个捆绑包封装为普通数据包。
            List<Packet<ClientGamePacketListener>> newSubPackets = new ArrayList<>();
            for (var subPacket : bundlePacket.subPackets()) {
                newSubPackets.add(createRedirectedMessage(
                    dimension, (Packet<ClientGamePacketListener>) subPacket
                ));
            }
            
            return new ClientboundBundlePacket(
                (List<Packet<? super ClientGamePacketListener>>) (List) newSubPackets
            );
        }
        else {
            Payload payload = new Payload(dimension, packet);
            
            // 自定义负载数据包也需要支持捆绑。
            // 捆绑包使用客户端游戏监听器类型，
            // 自定义负载使用客户端通用监听器类型。
            // 泛型参数只用于传入监听器，具有逆变性质，
            // 因此可按游戏监听器子类型适配。
            
            return (Packet<ClientGamePacketListener>) (Packet)
                new ClientboundCustomPayloadPacket(payload);
        }
    }
    
    /** 给指定玩家连接发指定维度的包装包。 */
    public static void sendRedirectedMessage(
        ServerPlayer player,
        ResourceKey<Level> dimension,
        Packet<ClientGamePacketListener> packet
    ) {
        player.connection.send(createRedirectedMessage(dimension, packet));
    }

    // 此判断不处理捆绑数据包。
    /** 检查ClientboundCustomPayloadPacket内部是否为本模块Payload。 */
    public static boolean isRedirectPacket(Packet<?> packet) {
        return packet instanceof ClientboundCustomPayloadPacket customPayloadPacket &&
            customPayloadPacket.payload() instanceof Payload;
    }
    
    // 使用原版协议抽象构造重定向数据包编解码器。
    private static final ProtocolInfo<ClientGamePacketListener> PLACEHOLDER_PROTOCOL_INFO =
        // 绑定函数负责将字节缓冲转换为注册表缓冲，
        // 作为协议输入的预处理。
        // 调用协议时传入的已是注册表缓冲，
        // 因此此处只需类型转换。
        GameProtocols.CLIENTBOUND_TEMPLATE.bind(
            argBuf -> ((RegistryFriendlyByteBuf) argBuf)
        );

    /** 维度ResourceKey与一个原版PLAY包的容器。 */
    public record Payload(
        ResourceKey<Level> dimension, Packet<? extends ClientGamePacketListener> packet
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Payload> TYPE =
            new Type<>(payloadId);

        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC =
            StreamCodec.of(
                (b, p) -> p.write(b), Payload::read
            );

        /** 写维度键后调用原版协议codec编码嵌套包。 */
        @SuppressWarnings("unchecked")
        public void write(RegistryFriendlyByteBuf buf) {
            Validate.notNull(packet, "packet is null");
            
            buf.writeResourceKey(dimension);
            
            PLACEHOLDER_PROTOCOL_INFO.codec()
                .encode(buf, (Packet<? super ClientGamePacketListener>) packet);
        }
        
        /** 读维度键并以原版协议codec解码嵌套包。 */
        @SuppressWarnings("unchecked")
        public static Payload read(FriendlyByteBuf buf) {
            ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
            
            var packet = (Packet<ClientGamePacketListener>)
                PLACEHOLDER_PROTOCOL_INFO.codec().decode(buf);
            
            return new Payload(dimension, packet);
        }

        /** 交给PacketRedirectionClient在正确客户端世界处理。 */
        @SuppressWarnings({"unchecked", "rawtypes"})
        // 此入口只由客户端调用。
        public void handle(ClientGamePacketListener listener) {
            PacketRedirectionClient.handleRedirectedPacket(
                dimension, (Packet) packet, listener
            );
        }

        /** 返回redirected类型。 */
        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

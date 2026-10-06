package com.xfw.shuttershadow.network;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xfw.shuttershadow.util.McHelper;
import com.xfw.shuttershadow.access.IEWorld;
import com.xfw.shuttershadow.mixin.minecraft.server.MixinServerGamePacketListenerImpl_Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class PacketRedirection {
    private static final Logger LOGGER = LoggerFactory.getLogger(PacketRedirection.class);
    
    // Packets from secondary dimensions use this Shuttershadow-owned channel.
    public static final ResourceLocation payloadId =
        McHelper.newResourceLocation("shuttershadow:redirect");
    
    private static final ThreadLocal<ResourceKey<Level>> serverPacketRedirection =
        ThreadLocal.withInitial(() -> null);

    public static void withForceRedirect(ServerLevel world, Runnable func) {
        withForceRedirectAndGet(world, () -> {
            func.run();
            return null;
        });
    }
    
    @SuppressWarnings("UnusedReturnValue")
    public static <T> T withForceRedirectAndGet(ServerLevel world, Supplier<T> func) {
        if (((IEWorld) world).portal_getThread() != Thread.currentThread()) {
            LOGGER.error(
                "It's possible that a mod is trying to handle packet in networking thread instead of server thread. This is not thread safe and can cause rare bugs! (Shuttershadow is checking the packet handling thread)",
                new Throwable()
            );
        }
        
        ResourceKey<Level> redirectDim = world.dimension();
        
        ResourceKey<Level> oldRedirection = serverPacketRedirection.get();
        
        if (oldRedirection != redirectDim) {
            serverPacketRedirection.set(redirectDim);
        }
        
        try {
            return func.get();
        }
        finally {
            if (oldRedirection != redirectDim) {
                serverPacketRedirection.set(oldRedirection);
            }
        }
    }
    
    /**
     * If it's not null, all sent packets will be wrapped into redirected packet
     * {@link MixinServerGamePacketListenerImpl_Redirect}
     */
    @Nullable
    public static ResourceKey<Level> getForceRedirectDimension() {
        return serverPacketRedirection.get();
    }
    
    // avoid duplicate redirect nesting
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
                    serverPlayNetworkHandler.player.server,
                    dimension,
                    packet
                )
            );
        }
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Packet<ClientGamePacketListener> createRedirectedMessage(
        MinecraftServer server,
        ResourceKey<Level> dimension,
        Packet<ClientGamePacketListener> packet
    ) {
        if (isRedirectPacket(packet)) {
            // avoid duplicate redirect nesting
            return packet;
        }
        
        Validate.isTrue(!(packet instanceof BundleDelimiterPacket));
        if (packet instanceof ClientboundBundlePacket bundlePacket) {
            // vanilla has special handling to bundle packet
            // don't wrap a bundle packet into a normal packet
            List<Packet<ClientGamePacketListener>> newSubPackets = new ArrayList<>();
            for (var subPacket : bundlePacket.subPackets()) {
                newSubPackets.add(createRedirectedMessage(
                    server, dimension, (Packet<ClientGamePacketListener>) subPacket
                ));
            }
            
            return new ClientboundBundlePacket(
                (List<Packet<? super ClientGamePacketListener>>) (List) newSubPackets
            );
        }
        else {
            Payload payload = new Payload(dimension, packet);
            
            // the custom payload packet should be able to be bundled
            // the bundle accepts Packet<ClientGamePacketListener>
            // but the custom payload packet is Packet<ClientCommonPacketListener>
            // the generic parameter is contravariant (it's used as argument),
            // which means changing it to subtype is fine
            
            return (Packet<ClientGamePacketListener>) (Packet)
                new ClientboundCustomPayloadPacket(payload);
        }
    }
    
    public static void sendRedirectedMessage(
        ServerPlayer player,
        ResourceKey<Level> dimension,
        Packet<ClientGamePacketListener> packet
    ) {
        player.connection.send(createRedirectedMessage(player.server, dimension, packet));
    }

    // Note this doesn't consider bundle packet
    public static boolean isRedirectPacket(Packet<?> packet) {
        return packet instanceof ClientboundCustomPayloadPacket customPayloadPacket &&
            customPayloadPacket.payload() instanceof Payload;
    }
    
    // Mojang's new networking abstraction made packet redirection more convoluted...
    private static final ProtocolInfo<ClientGamePacketListener> PLACEHOLDER_PROTOCOL_INFO =
        // the function passed into bind is used for converting ByteBuf to RegistryFriendlyByteBuf
        // it's a pre-processor
        // that ProtocolInfo will be used by passing RegistryFriendlyByteBuf
        // so only a casting is needed
        GameProtocols.CLIENTBOUND_TEMPLATE.bind(
            argBuf -> ((RegistryFriendlyByteBuf) argBuf)
        );

    public record Payload(
        ResourceKey<Level> dimension, Packet<? extends ClientGamePacketListener> packet
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Payload> TYPE =
            new Type<>(ResourceLocation.parse(payloadId.toString()));

        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC =
            StreamCodec.of(
                (b, p) -> p.write(b), Payload::read
            );

        @SuppressWarnings("unchecked")
        public void write(RegistryFriendlyByteBuf buf) {
            Validate.notNull(packet, "packet is null");
            
            buf.writeResourceKey(dimension);
            
            PLACEHOLDER_PROTOCOL_INFO.codec()
                .encode(buf, (Packet<? super ClientGamePacketListener>) packet);
        }
        
        @SuppressWarnings("unchecked")
        public static Payload read(FriendlyByteBuf buf) {
            ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
            
            var packet = (Packet<ClientGamePacketListener>)
                PLACEHOLDER_PROTOCOL_INFO.codec().decode(buf);
            
            return new Payload(dimension, packet);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        //@OnlyIn(Dist.CLIENT)
        public void handle(ClientGamePacketListener listener) {
            PacketRedirectionClient.handleRedirectedPacket(
                dimension, (Packet) packet, listener
            );
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

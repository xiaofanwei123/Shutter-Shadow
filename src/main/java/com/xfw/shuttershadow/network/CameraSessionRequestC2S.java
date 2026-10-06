package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Opens a remote scene subscription; normal server ticks maintain it. */
public record CameraSessionRequestC2S(long sequence, ResourceLocation filterId,
                                     ResourceLocation targetDimension, int cameraStandId)
        implements CustomPacketPayload {
    public static final Type<CameraSessionRequestC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "camera_session_request"));
    public static final StreamCodec<FriendlyByteBuf, CameraSessionRequestC2S> STREAM_CODEC = StreamCodec.of(
            CameraSessionRequestC2S::encode, CameraSessionRequestC2S::decode);

    private static void encode(FriendlyByteBuf buf, CameraSessionRequestC2S value) {
        buf.writeVarLong(value.sequence());
        buf.writeResourceLocation(value.filterId());
        buf.writeResourceLocation(value.targetDimension());
        buf.writeVarInt(value.cameraStandId());
    }

    private static CameraSessionRequestC2S decode(FriendlyByteBuf buf) {
        return new CameraSessionRequestC2S(buf.readVarLong(), buf.readResourceLocation(),
                buf.readResourceLocation(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

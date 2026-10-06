package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client confirmation that IP has switched the local player to the target level. */
public record DimensionFilmReadyC2S(long transaction) implements CustomPacketPayload {
    public static final Type<DimensionFilmReadyC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "dimension_film_ready"));
    public static final StreamCodec<FriendlyByteBuf, DimensionFilmReadyC2S> STREAM_CODEC = StreamCodec.of(
            DimensionFilmReadyC2S::encode, DimensionFilmReadyC2S::decode);

    private static void encode(FriendlyByteBuf buf, DimensionFilmReadyC2S value) {
        buf.writeVarLong(value.transaction());
    }

    private static DimensionFilmReadyC2S decode(FriendlyByteBuf buf) {
        return new DimensionFilmReadyC2S(buf.readVarLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

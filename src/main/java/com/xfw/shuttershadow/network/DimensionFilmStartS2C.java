package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Starts the client-side acknowledgement window for a physical selfie. */
public record DimensionFilmStartS2C(long transaction, ResourceLocation dimension)
        implements CustomPacketPayload {
    public static final Type<DimensionFilmStartS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "dimension_film_start"));
    public static final StreamCodec<FriendlyByteBuf, DimensionFilmStartS2C> STREAM_CODEC = StreamCodec.of(
            DimensionFilmStartS2C::encode, DimensionFilmStartS2C::decode);

    private static void encode(FriendlyByteBuf buf, DimensionFilmStartS2C value) {
        buf.writeVarLong(value.transaction());
        buf.writeResourceLocation(value.dimension());
    }

    private static DimensionFilmStartS2C decode(FriendlyByteBuf buf) {
        return new DimensionFilmStartS2C(buf.readVarLong(), buf.readResourceLocation());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

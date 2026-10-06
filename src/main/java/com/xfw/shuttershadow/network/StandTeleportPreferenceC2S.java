package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Player preference for being teleported by a dimension-film camera stand. */
public record StandTeleportPreferenceC2S(boolean accepted) implements CustomPacketPayload {
    public static final Type<StandTeleportPreferenceC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "stand_teleport_preference"));
    public static final StreamCodec<FriendlyByteBuf, StandTeleportPreferenceC2S> STREAM_CODEC =
            StreamCodec.of(StandTeleportPreferenceC2S::encode, StandTeleportPreferenceC2S::decode);

    private static void encode(FriendlyByteBuf buf, StandTeleportPreferenceC2S value) {
        buf.writeBoolean(value.accepted());
    }

    private static StandTeleportPreferenceC2S decode(FriendlyByteBuf buf) {
        return new StandTeleportPreferenceC2S(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

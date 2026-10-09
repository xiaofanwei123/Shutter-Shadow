package com.xfw.dimensionalexposure.network;

import com.xfw.dimensionalexposure.DimensionalExposure;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端同步支架传送及其他玩家胶卷传送的个人接受选项。 */
public record CameraTeleportPreferenceC2S(boolean acceptStand, boolean acceptOtherPlayers)
        implements CustomPacketPayload {
    public static final Type<CameraTeleportPreferenceC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimensionalExposure.MODID, "camera_teleport_preference"));
    public static final StreamCodec<FriendlyByteBuf, CameraTeleportPreferenceC2S> STREAM_CODEC =
            StreamCodec.of(CameraTeleportPreferenceC2S::encode, CameraTeleportPreferenceC2S::decode);

    /** 固定顺序写入两种个人接受选项。 */
    private static void encode(FriendlyByteBuf buf, CameraTeleportPreferenceC2S value) {
        buf.writeBoolean(value.acceptStand());
        buf.writeBoolean(value.acceptOtherPlayers());
    }

    /** 按相同顺序读取个人接受选项。 */
    private static CameraTeleportPreferenceC2S decode(FriendlyByteBuf buf) {
        return new CameraTeleportPreferenceC2S(buf.readBoolean(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

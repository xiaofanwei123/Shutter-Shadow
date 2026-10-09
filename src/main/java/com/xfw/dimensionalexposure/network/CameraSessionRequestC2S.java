package com.xfw.dimensionalexposure.network;

import com.xfw.dimensionalexposure.DimensionalExposure;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端请求远维度预览：序号、滤镜物品ID、组件目标维度、支架ID。 */
public record CameraSessionRequestC2S(long sequence, ResourceLocation filterId,
                                     ResourceLocation targetDimension, int cameraStandId)
        implements CustomPacketPayload {
    public static final Type<CameraSessionRequestC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimensionalExposure.MODID, "camera_session_request"));
    public static final StreamCodec<FriendlyByteBuf, CameraSessionRequestC2S> STREAM_CODEC = StreamCodec.of(
            CameraSessionRequestC2S::encode, CameraSessionRequestC2S::decode);

    /** 按序写VarLong序号、两个资源ID与VarInt支架ID。 */
    private static void encode(FriendlyByteBuf buf, CameraSessionRequestC2S value) {
        buf.writeVarLong(value.sequence());
        buf.writeResourceLocation(value.filterId());
        buf.writeResourceLocation(value.targetDimension());
        buf.writeVarInt(value.cameraStandId());
    }

    /** 按相同字段顺序解码请求。 */
    private static CameraSessionRequestC2S decode(FriendlyByteBuf buf) {
        return new CameraSessionRequestC2S(buf.readVarLong(), buf.readResourceLocation(),
                buf.readResourceLocation(), buf.readVarInt());
    }

    /** 返回camera_session_request包类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

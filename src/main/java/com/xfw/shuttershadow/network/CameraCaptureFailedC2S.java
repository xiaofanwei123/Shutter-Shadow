package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端报告图片处理失败，服务端只终结属于该玩家的已登记拍摄。 */
public record CameraCaptureFailedC2S(String exposureId) implements CustomPacketPayload {
    public static final Type<CameraCaptureFailedC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "capture_failed"));
    public static final StreamCodec<FriendlyByteBuf, CameraCaptureFailedC2S> STREAM_CODEC = StreamCodec.of(
            CameraCaptureFailedC2S::encode, CameraCaptureFailedC2S::decode);
    /** 写入长度受限的照片编号。 */
    private static void encode(FriendlyByteBuf buf, CameraCaptureFailedC2S value) { buf.writeUtf(value.exposureId(), 1024); }
    /** 读取照片编号，不接受任意长字符串。 */
    private static CameraCaptureFailedC2S decode(FriendlyByteBuf buf) { return new CameraCaptureFailedC2S(buf.readUtf(1024)); }
    /** 返回图片失败包类型。 */
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

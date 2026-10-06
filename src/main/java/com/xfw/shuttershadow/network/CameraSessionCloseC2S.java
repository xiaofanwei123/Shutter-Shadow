package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import io.github.mortuusars.exposure.util.ExtraData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端关闭预览或反馈后台照片结果。 */
public record CameraSessionCloseC2S(long sequence, boolean captured) implements CustomPacketPayload {
    /** 红石只向原生截图传递事务编号，不携带目标维度渲染场景。 */
    public static final ExtraData.Type<Long> SOURCE_CAPTURE_SEQUENCE =
            ExtraData.Type.longVal("shuttershadow_source_stand_capture");
    /** 单参数构造器默认captured=false，用于单纯关闭会话。 */
    public CameraSessionCloseC2S(long sequence) { this(sequence, false); }
    public static final Type<CameraSessionCloseC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "camera_session_close"));
    public static final StreamCodec<FriendlyByteBuf, CameraSessionCloseC2S> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeVarLong(value.sequence());
                buf.writeBoolean(value.captured());
            },
            buf -> new CameraSessionCloseC2S(buf.readVarLong(), buf.readBoolean()));

    /** 返回camera_session_close包类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

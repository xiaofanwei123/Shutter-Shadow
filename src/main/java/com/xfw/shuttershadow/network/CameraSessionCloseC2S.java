package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import io.github.mortuusars.exposure.util.ExtraData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 关闭手动取景，或回报支架截图结果；成功后由服务端提交照片并执行传送。 */
public record CameraSessionCloseC2S(long sequence, boolean captured) implements CustomPacketPayload {
    /** 红石只向原生截图传递事务编号，不携带目标维度渲染场景。 */
    public static final ExtraData.Type<Long> SOURCE_CAPTURE_SEQUENCE =
            ExtraData.Type.longVal("shuttershadow_source_stand_capture");
    public CameraSessionCloseC2S(long sequence) { this(sequence, false); }
    public static final Type<CameraSessionCloseC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "camera_session_close"));
    public static final StreamCodec<FriendlyByteBuf, CameraSessionCloseC2S> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeVarLong(value.sequence());
                buf.writeBoolean(value.captured());
            },
            buf -> new CameraSessionCloseC2S(buf.readVarLong(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 0表示结束预览/真实传送后清理。 */
public record RemoteSceneStopS2C(long captureSequence) implements CustomPacketPayload {
    /** 无参构造器使用序号0。 */
    public RemoteSceneStopS2C() { this(0L); }
    public static final Type<RemoteSceneStopS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "remote_scene_stop"));
    public static final StreamCodec<FriendlyByteBuf, RemoteSceneStopS2C> STREAM_CODEC =
            StreamCodec.of((buf, value) -> buf.writeVarLong(value.captureSequence()),
                    buf -> new RemoteSceneStopS2C(buf.readVarLong()));

    /** 返回remote_scene_stop类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

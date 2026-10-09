package com.xfw.dimensionalexposure.network;

import com.xfw.dimensionalexposure.DimensionalExposure;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 按序号区分后台截图取消和传送后的场景清理。 */
public record RemoteSceneStopS2C(long captureSequence) implements CustomPacketPayload {
    /** 无参构造器使用序号0。 */
    public RemoteSceneStopS2C() { this(0L); }
    public static final Type<RemoteSceneStopS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimensionalExposure.MODID, "remote_scene_stop"));
    public static final StreamCodec<FriendlyByteBuf, RemoteSceneStopS2C> STREAM_CODEC =
            StreamCodec.of((buf, value) -> buf.writeVarLong(value.captureSequence()),
                    buf -> new RemoteSceneStopS2C(buf.readVarLong()));

    /** 返回remote_scene_stop类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

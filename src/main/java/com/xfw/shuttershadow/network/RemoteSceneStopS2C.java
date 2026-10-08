package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 区分后台截图取消、传送清理和服务端要求关闭相机。 */
public record RemoteSceneStopS2C(long captureSequence, boolean closeCamera) implements CustomPacketPayload {
    /** 无参构造器使用序号0。 */
    public RemoteSceneStopS2C() { this(0L, false); }
    /** 普通停止只清理场景，不停用正在使用的相机。 */
    public RemoteSceneStopS2C(long captureSequence) { this(captureSequence, false); }
    public static final Type<RemoteSceneStopS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "remote_scene_stop"));
    public static final StreamCodec<FriendlyByteBuf, RemoteSceneStopS2C> STREAM_CODEC =
            StreamCodec.of((buf, value) -> {
                buf.writeVarLong(value.captureSequence());
                buf.writeBoolean(value.closeCamera());
            }, buf -> new RemoteSceneStopS2C(buf.readVarLong(), buf.readBoolean()));

    /** 返回remote_scene_stop类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

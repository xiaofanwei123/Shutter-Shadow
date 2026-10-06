package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 独立的远区块发送回执，不能占用原版PlayerChunkSender回执。 */
public record RemoteChunkBatchReceivedC2S(float desiredChunksPerTick) implements CustomPacketPayload {
    public static final Type<RemoteChunkBatchReceivedC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "remote_chunk_batch_received"));
    public static final StreamCodec<FriendlyByteBuf, RemoteChunkBatchReceivedC2S> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> buf.writeFloat(value.desiredChunksPerTick()),
            buf -> new RemoteChunkBatchReceivedC2S(buf.readFloat()));

    /** 排入服务端线程更新该玩家远批次目标吞吐。 */
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RemoteChunkTracking.getPlayerInfo(player).onChunkBatchReceivedByClient(desiredChunksPerTick);
            }
        });
    }

    /** 返回remote_chunk_batch_received类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

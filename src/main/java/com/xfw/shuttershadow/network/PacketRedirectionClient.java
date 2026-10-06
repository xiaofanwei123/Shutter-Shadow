package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.mixin.minecraft.client.MixinMinecraft_RedirectedPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class PacketRedirectionClient {
    private static ChunkBatchSizeCalculator remoteBatchCalculator = new ChunkBatchSizeCalculator();

    public static void resetChunkBatchCalculator() {
        remoteBatchCalculator = new ChunkBatchSizeCalculator();
    }

    /**
     * 保留异维度数据包异步任务的世界上下文。
     * {@link MixinMinecraft_RedirectedPacket}
     */
    public static final ThreadLocal<ResourceKey<Level>> clientTaskRedirection =
            ThreadLocal.withInitial(() -> null);

    public static boolean getIsProcessingRedirectedMessage() {
        return clientTaskRedirection.get() != null;
    }

    /** 维度键直接来自数据包，不再依赖登录时建立的整数映射。 */
    public static void handleRedirectedPacket(ResourceKey<Level> dimension,
            Packet<ClientGamePacketListener> packet, ClientGamePacketListener handler) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> handleRedirectedPacket(dimension, packet, handler));
            return;
        }

        // 原版批次交给 ClientPacketListener；仅包装的相机批次走独立回执。
        if (packet instanceof ClientboundChunkBatchStartPacket) {
            remoteBatchCalculator.onBatchStart();
            return;
        }
        if (packet instanceof ClientboundChunkBatchFinishedPacket finished) {
            remoteBatchCalculator.onBatchFinished(finished.batchSize());
            PacketDistributor.sendToServer(new RemoteChunkBatchReceivedC2S(remoteBatchCalculator.getDesiredChunksPerTick()));
            return;
        }

        ResourceKey<Level> oldTaskRedirection = clientTaskRedirection.get();
        clientTaskRedirection.set(dimension);
        try {
            ClientWorldLoader.withSwitchedWorldFailSoft(dimension, () -> packet.handle(handler));
        } finally {
            clientTaskRedirection.set(oldTaskRedirection);
        }
    }
}

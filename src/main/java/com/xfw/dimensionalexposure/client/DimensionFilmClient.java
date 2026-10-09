package com.xfw.dimensionalexposure.client;

import com.xfw.dimensionalexposure.DimensionalExposure;
import com.xfw.dimensionalexposure.DimensionalExposureConfig;
import com.xfw.dimensionalexposure.network.CameraTeleportPreferenceC2S;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** 连接期间同步玩家维度胶卷传送的个人接受选项。 */
@EventBusSubscriber(modid = DimensionalExposure.MODID, value = Dist.CLIENT)
public final class DimensionFilmClient {
    private static CameraTeleportPreferenceC2S lastSentPreference;

    /** 禁止实例化此工具类。 */
    private DimensionFilmClient() {
    }

    /** 首次连接或配置变更时发送一次，避免每刻重复发送相同选项。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) return;
        boolean acceptStand = DimensionalExposureConfig.acceptStandDimensionFilmTeleport();
        boolean acceptOtherPlayers = DimensionalExposureConfig.acceptOtherPlayerDimensionFilmTeleport();
        if (lastSentPreference == null || lastSentPreference.acceptStand() != acceptStand
                || lastSentPreference.acceptOtherPlayers() != acceptOtherPlayers) {
            var preference = new CameraTeleportPreferenceC2S(acceptStand, acceptOtherPlayers);
            PacketDistributor.sendToServer(preference);
            lastSentPreference = preference;
        }
    }

    /** 下次连接重新发送，服务端不沿用上一个连接的接受状态。 */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        lastSentPreference = null;
    }
}


package com.xfw.shuttershadow.client;

import com.xfw.shuttershadow.Shuttershadow;
import com.xfw.shuttershadow.ShuttershadowConfig;
import com.xfw.shuttershadow.network.DimensionFilmReadyC2S;
import com.xfw.shuttershadow.network.DimensionFilmStartS2C;
import com.xfw.shuttershadow.network.StandTeleportPreferenceC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Confirms the IP client-side dimension switch before Exposure captures. */
@EventBusSubscriber(modid = Shuttershadow.MODID, value = Dist.CLIENT)
public final class DimensionFilmClient {
    private static long pendingTransaction = Long.MIN_VALUE;
    private static ResourceLocation pendingDimension;
    private static Boolean lastSentStandPreference;

    private DimensionFilmClient() {
    }

    public static void start(DimensionFilmStartS2C message) {
        pendingTransaction = message.transaction();
        pendingDimension = message.dimension();
        tryReady();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        syncStandPreference();
        tryReady();
    }

    /** Sends the local config once after joining and again only when it changes. */
    private static void syncStandPreference() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) return;

        boolean accepted = ShuttershadowConfig.acceptStandDimensionFilmTeleport();
        if (lastSentStandPreference == null || lastSentStandPreference != accepted) {
            lastSentStandPreference = accepted;
            PacketDistributor.sendToServer(new StandTeleportPreferenceC2S(accepted));
        }
    }

    private static void tryReady() {
        if (pendingDimension == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null
                || minecraft.level == null) return;
        if (!minecraft.level.dimension().location().equals(pendingDimension)) return;

        long transaction = pendingTransaction;
        pendingTransaction = Long.MIN_VALUE;
        pendingDimension = null;
        PacketDistributor.sendToServer(new DimensionFilmReadyC2S(transaction));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        pendingTransaction = Long.MIN_VALUE;
        pendingDimension = null;
        lastSentStandPreference = null;
    }
}


package com.xfw.shuttershadow.core.teleportation;


import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.util.McHelper;
import com.xfw.shuttershadow.access.IEAbstractClientPlayer;
import com.xfw.shuttershadow.access.IEClientPlayNetworkHandler;
import com.xfw.shuttershadow.access.IEEntity;
import com.xfw.shuttershadow.access.IEGameRenderer;
import com.xfw.shuttershadow.access.IEMinecraftClient;
import com.xfw.shuttershadow.access.IEParticleManager;
import com.xfw.shuttershadow.network.PacketRedirectionClient;
import com.xfw.shuttershadow.core.render.MyGameRenderer;
import com.xfw.shuttershadow.core.render.FogRendererContext;
import com.xfw.shuttershadow.core.render.RenderStates;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;
import com.xfw.shuttershadow.util.Helper;

/** 客户端真实无缝换维度，复用已观察的ClientLevel/renderer与原LocalPlayer，避免传统respawn加载屏。 */
public class ClientTeleportationManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Minecraft client = Minecraft.getInstance();

    /** 目标不同先changePlayerDimension，随后设脚底位置、调整载具、更新本帧状态并要求一次原版地形setup。 */
    public static void forceTeleportPlayer(ResourceKey<Level> toDimension, Vec3 destination) {
        LOGGER.info("client player force teleported {} {}", toDimension.location(), destination);
        
        ClientLevel fromWorld = client.level;
        assert fromWorld != null;
        ResourceKey<Level> fromDimension = fromWorld.dimension();
        LocalPlayer player = client.player;
        assert player != null;
        if (fromDimension != toDimension) {
            ClientLevel toWorld = ClientWorldLoader.getWorld(toDimension);
            Vec3 eyeOffset = McHelper.getEyeOffset(player);
            changePlayerDimension(player, fromWorld, toWorld, destination.add(eyeOffset));
        }
        
        player.setPos(destination.x, destination.y, destination.z);
        McHelper.adjustVehicle(player);
        
        
        RenderStates.updatePreRenderInfo(RenderStates.getPartialTick());
        MyGameRenderer.vanillaTerrainSetupOverride = 1;
    }

    /** 取消骑乘，切网络handler world。 */
    public static void changePlayerDimension(
        LocalPlayer player, ClientLevel fromWorld, ClientLevel toWorld, Vec3 newEyePos
    ) {
        Validate.isTrue(!WorldRenderInfo.isRendering());
        Validate.isTrue(!PacketRedirectionClient.getIsProcessingRedirectedMessage());
        
        Entity vehicle = player.getVehicle();
        player.unRide();
        
        ResourceKey<Level> toDimension = toWorld.dimension();
        ResourceKey<Level> fromDimension = fromWorld.dimension();
        
        ((IEClientPlayNetworkHandler) client.getConnection()).ip_setWorld(toWorld);
        
        fromWorld.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        
        ((IEEntity) player).ip_setWorld(toWorld);
        
        McHelper.setEyePos(player, newEyePos, newEyePos);
        McHelper.updateBoundingBox(player);
        
        ((IEEntity) player).ip_unsetRemoved();
        
        toWorld.addEntity(player);
        ((IEAbstractClientPlayer) player).ip_setClientLevel(toWorld);
        
        IEGameRenderer gameRenderer = (IEGameRenderer) Minecraft.getInstance().gameRenderer;
        gameRenderer.ip_setLightmapTextureManager(ClientWorldLoader
            .getDimensionRenderHelper(toDimension).lightmapTexture);
        
        client.level = toWorld;
        ((IEMinecraftClient) client).ip_setWorldRenderer(
            ClientWorldLoader.getWorldRenderer(toDimension)
        );
        
        if (client.particleEngine != null) {
            // 切换世界时保留已有粒子。
            ((IEParticleManager) client.particleEngine).ip_setWorld(toWorld);
        }
        
        client.getBlockEntityRenderDispatcher().setLevel(toWorld);
        
        if (vehicle != null) {
            Vec3 offset = McHelper.getVehicleOffsetFromPassenger(vehicle, player);
            Vec3 vehiclePos = player.position().add(offset);
            moveClientEntityAcrossDimension(
                vehicle, toWorld,
                vehiclePos
            );
            McHelper.setPosAndLastTickPos(
                vehicle,
                player.position().add(offset),
                McHelper.lastTickPosOf(player).add(offset)
            );
            player.startRiding(vehicle, true);
        }
        
        Helper.log(String.format(
            "Client Changed Dimension from %s to %s time: %s age: %s",
            fromDimension.location(),
            toDimension.location(),
            toWorld.getGameTime(),
            player.tickCount
        ));
        
        FogRendererContext.onPlayerTeleport(fromDimension, toDimension);
        
    }

    /** 从旧ClientLevel移除普通客户端实体，换level/位置、清removed并加入目标，验证存活标记。 */
    public static void moveClientEntityAcrossDimension(
        Entity entity,
        ClientLevel newWorld,
        Vec3 newPos
    ) {
        ClientLevel oldWorld = (ClientLevel) entity.level();
        oldWorld.removeEntity(entity.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        ((IEEntity) entity).ip_setWorld(newWorld);
        entity.setPos(newPos.x, newPos.y, newPos.z);
        ((IEEntity) entity).ip_unsetRemoved();
        newWorld.addEntity(entity);
        Validate.isTrue(!entity.isRemoved());
    }
}

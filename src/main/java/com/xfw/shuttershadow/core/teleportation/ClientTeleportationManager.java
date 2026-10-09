package com.xfw.shuttershadow.core.teleportation;


import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
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
import com.xfw.shuttershadow.core.render.RemoteViewArea;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

/** 客户端真实无缝换维度，复用已观察的ClientLevel/renderer与原LocalPlayer，避免传统respawn加载屏。 */
public class ClientTeleportationManager {
    public static final Minecraft client = Minecraft.getInstance();

    /** 解除骑乘，只切换玩家世界与位置，并刷新本帧地形状态。 */
    public static void forceTeleportPlayer(ResourceKey<Level> toDimension, Vec3 destination) {
        
        ClientLevel fromWorld = client.level;
        assert fromWorld != null;
        ResourceKey<Level> fromDimension = fromWorld.dimension();
        LocalPlayer player = client.player;
        assert player != null;
        player.unRide();
        if (fromDimension != toDimension) {
            ClientLevel toWorld = ClientWorldLoader.getWorld(toDimension);
            Vec3 eyeOffset = McHelper.getEyeOffset(player);
            changePlayerDimension(player, fromWorld, toWorld, destination.add(eyeOffset));
        }
        
        player.setPos(destination.x, destination.y, destination.z);
        
        
        RenderStates.updatePreRenderInfo(RenderStates.getPartialTick());
        MyGameRenderer.vanillaTerrainSetupOverride = 1;
    }

    /** 复用玩家与已加载世界资源完成无缝换维。 */
    public static void changePlayerDimension(
        LocalPlayer player, ClientLevel fromWorld, ClientLevel toWorld, Vec3 newEyePos
    ) {
        Validate.isTrue(!WorldRenderInfo.isRendering());
        Validate.isTrue(!PacketRedirectionClient.getIsProcessingRedirectedMessage());
        
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
        RemoteViewArea.onPlayerDimensionChanged();
        
        if (client.particleEngine != null) {
            // 切换世界时保留已有粒子。
            ((IEParticleManager) client.particleEngine).ip_setWorld(toWorld);
        }
        
        client.getBlockEntityRenderDispatcher().setLevel(toWorld);
        
        FogRendererContext.onPlayerTeleport(fromDimension, toDimension);
        
    }

}

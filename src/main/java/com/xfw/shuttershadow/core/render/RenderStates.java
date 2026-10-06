package com.xfw.shuttershadow.core.render;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.access.IEGameRenderer;
import com.xfw.shuttershadow.mixin.minecraft.client.IEParticle;

public class RenderStates {
    
    public static ResourceKey<Level> originalPlayerDimension;
    
    /**
     * It will be 0 right after ticking.
     */
    private static float partialTick = 0;
    
    public static void updatePreRenderInfo(
        float newPartialTick
    ) {
        ClientWorldLoader.initializeIfNeeded();
        
        Entity cameraEntity = MyRenderHelper.client.cameraEntity;
        
        if (cameraEntity == null) {
            return;
        }
        
        originalPlayerDimension = cameraEntity.level().dimension();
        partialTick = newPartialTick;
        
        FogRendererContext.update();
        
    }
    
    public static double getViewBobbingOffsetMultiplier() {
        return WorldRenderInfo.isRendering() ? 0 : 1;
    }
    
    public static void onTotalRenderEnd() {
        Minecraft client = Minecraft.getInstance();
        IEGameRenderer gameRenderer = (IEGameRenderer) Minecraft.getInstance().gameRenderer;
        gameRenderer.ip_setLightmapTextureManager(ClientWorldLoader
            .getDimensionRenderHelper(client.level.dimension()).lightmapTexture);
        
    }
    
    public static boolean isDimensionRendered(ResourceKey<Level> dimensionType) {
        return dimensionType == originalPlayerDimension;
    }
    
    public static boolean shouldRenderParticle(Particle particle) {
        return ((IEParticle) particle).portal_getWorld() == Minecraft.getInstance().level;
    }
    
    public static void setPartialTick(float partialTick_) {
        partialTick = partialTick_;
    }
    
    /**
     * This does not always equal Minecraft.getFrameTime.
     * It will be 0 right after ticking.
     */
    public static float getPartialTick() {
        return partialTick;
    }
    
}

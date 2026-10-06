package com.xfw.shuttershadow.core.render;


import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.access.IEGameRenderer;
import com.xfw.shuttershadow.mixin.minecraft.client.IEParticle;

/** 保存本帧来源维度与插值进度，区分远景和正常渲染。 */
public class RenderStates {
    
    public static ResourceKey<Level> originalPlayerDimension;
    
    /** 游戏刻刚结束时，此值为零。 */
    private static float partialTick = 0;
    
    /** 更新本帧来源维度、插值进度和雾渲染上下文。 */
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
    
    /** 目标渲染为0禁止视角摇晃，正常为1。 */
    public static double getViewBobbingOffsetMultiplier() {
        return WorldRenderInfo.isRendering() ? 0 : 1;
    }
    
    /** 每帧结束后恢复真实玩家世界的光照纹理。 */
    public static void onTotalRenderEnd() {
        Minecraft client = Minecraft.getInstance();
        IEGameRenderer gameRenderer = (IEGameRenderer) Minecraft.getInstance().gameRenderer;
        gameRenderer.ip_setLightmapTextureManager(ClientWorldLoader
            .getDimensionRenderHelper(client.level.dimension()).lightmapTexture);
        
    }
    
    /** 检查给定维度是否本帧真实来源维度。 */
    public static boolean isDimensionRendered(ResourceKey<Level> dimensionType) {
        return dimensionType == originalPlayerDimension;
    }
    
    /** 判断粒子是否属于当前正在绘制的世界。 */
    public static boolean shouldRenderParticle(Particle particle) {
        return ((IEParticle) particle).portal_getWorld() == Minecraft.getInstance().level;
    }
    
    /** 更新本帧插值进度。 */
    public static void setPartialTick(float partialTick_) {
        partialTick = partialTick_;
    }
    
    /** 取得本帧插值进度。 */
    public static float getPartialTick() {
        return partialTick;
    }
    
}

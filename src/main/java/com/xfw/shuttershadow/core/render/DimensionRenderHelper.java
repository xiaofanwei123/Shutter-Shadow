package com.xfw.shuttershadow.core.render;


import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;

/** 每维度光照纹理。 */
public class DimensionRenderHelper {
    private static final Minecraft client = Minecraft.getInstance();
    public final Level world;
    public final LightTexture lightmapTexture;
    
    /** 保存世界并复用主lightmap或建立次级LightTexture。 */
    public DimensionRenderHelper(Level world) {
        this.world = world;
        if (client.level == world) {
            lightmapTexture = client.gameRenderer.lightTexture();
        }
        else {
            lightmapTexture = new LightTexture(client.gameRenderer, client);
        }
    }
    
    /** 只tick非当前主lightmap的纹理。 */
    public void tick() {
        if (lightmapTexture != client.gameRenderer.lightTexture()) {
            lightmapTexture.tick();
        }
    }
    
    /** 只release非当前主lightmap的texture location，避免重复释放正在使用的主纹理。 */
    public void cleanUp() {
        if (lightmapTexture != client.gameRenderer.lightTexture()) {
            // release 同时移除注册并关闭纹理，避免资源重载再次访问已释放的贴图。
            client.getTextureManager().release(lightmapTexture.lightTextureLocation);
        }
    }
    
}

package com.xfw.shuttershadow.core.render;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;
import com.xfw.shuttershadow.util.Helper;

public class DimensionRenderHelper {
    private static final Minecraft client = Minecraft.getInstance();
    public final Level world;
    public final LightTexture lightmapTexture;
    
    public DimensionRenderHelper(Level world) {
        this.world = world;
        if (client.level == world) {
            lightmapTexture = client.gameRenderer.lightTexture();
        }
        else {
            lightmapTexture = new LightTexture(client.gameRenderer, client);
            Helper.log("Created lightmap texture for " + world.dimension().location());
        }
    }
    
    public void tick() {
        if (lightmapTexture != client.gameRenderer.lightTexture()) {
            lightmapTexture.tick();
        }
    }
    
    public void cleanUp() {
        if (lightmapTexture != client.gameRenderer.lightTexture()) {
            // release 同时移除注册并关闭纹理，避免资源重载再次访问已释放的贴图。
            client.getTextureManager().release(lightmapTexture.lightTextureLocation);
        }
    }
    
}

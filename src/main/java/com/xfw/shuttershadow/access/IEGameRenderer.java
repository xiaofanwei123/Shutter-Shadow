package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;

public interface IEGameRenderer {
    void ip_setLightmapTextureManager(LightTexture manager);
    
    boolean ip_getDoRenderHand();
    
    void ip_setCamera(Camera camera);
    
}

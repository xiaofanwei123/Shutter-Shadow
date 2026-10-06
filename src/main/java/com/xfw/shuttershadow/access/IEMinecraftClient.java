package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;

public interface IEMinecraftClient {
    void ip_setWorldRenderer(LevelRenderer r);
    
    void ip_setRenderBuffers(RenderBuffers arg);
    
    Thread ip_getRunningThread();
}

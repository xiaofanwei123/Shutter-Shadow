package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;

public interface IEWorldRenderer {
    EntityRenderDispatcher ip_getEntityRenderDispatcher();
    
    ViewArea ip_getBuiltChunkStorage();
    
    PostChain portal_getTransparencyShader();
    
    void portal_setTransparencyShader(PostChain arg);
    
    RenderBuffers ip_getRenderBuffers();
    
    void ip_setRenderBuffers(RenderBuffers arg);
    
    Frustum portal_getFrustum();
    
    void portal_setFrustum(Frustum arg);
    
    void portal_fullyDispose();
    
    void portal_setChunkInfoList(ObjectArrayList<SectionRenderDispatcher.RenderSection> arg);
    
    ObjectArrayList<SectionRenderDispatcher.RenderSection> portal_getChunkInfoList();
}

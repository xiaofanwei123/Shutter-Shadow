package com.xfw.shuttershadow.access;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;

/** MixinLevelRenderer实现的renderer内部状态桥。 */
public interface IEWorldRenderer {
    /** 取得实体渲染dispatcher。 */
    EntityRenderDispatcher ip_getEntityRenderDispatcher();
    
    /** 取得ViewArea存储。 */
    ViewArea ip_getBuiltChunkStorage();
    
    /** 取得透明后处理PostChain。 */
    PostChain portal_getTransparencyShader();
    
    /** 替换透明后处理PostChain。 */
    void portal_setTransparencyShader(PostChain arg);
    
    /** 取得renderer当前RenderBuffers。 */
    RenderBuffers ip_getRenderBuffers();
    
    /** 替换renderer RenderBuffers。 */
    void ip_setRenderBuffers(RenderBuffers arg);
    
    /** 取得剔除frustum。 */
    Frustum portal_getFrustum();
    
    /** 替换剔除frustum。 */
    void portal_setFrustum(Frustum arg);
    
    /** 关闭section dispatcher和目标renderer剩余资源。 */
    void portal_fullyDispose();
    
    /** 替换可见RenderSection列表。 */
    void portal_setChunkInfoList(ObjectArrayList<SectionRenderDispatcher.RenderSection> arg);
    
    /** 取得可见RenderSection列表。 */
    ObjectArrayList<SectionRenderDispatcher.RenderSection> portal_getChunkInfoList();
}

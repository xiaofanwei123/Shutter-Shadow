package com.xfw.dimensionalexposure.access;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;

/** MixinGameRenderer实现的渲染引用桥，远场try/finally保存恢复使用。 */
public interface IEGameRenderer {
    /** 替换GameRenderer的LightTexture。 */
    void ip_setLightmapTextureManager(LightTexture manager);
    
    /** 取得当前是否渲染手的字段。 */
    boolean ip_getDoRenderHand();
    
    /** 替换主Camera。 */
    void ip_setCamera(Camera camera);
    
}

package com.xfw.dimensionalexposure.access;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;

/** MixinMinecraft实现的Minecraft内部引用桥。 */
public interface IEMinecraftClient {
    /** 替换当前LevelRenderer。 */
    void ip_setWorldRenderer(LevelRenderer r);
    
    /** 替换当前RenderBuffers。 */
    void ip_setRenderBuffers(RenderBuffers arg);
    
    /** 返回Minecraft运行线程。 */
    Thread ip_getRunningThread();
}

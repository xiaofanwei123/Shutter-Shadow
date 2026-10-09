package com.xfw.dimensionalexposure.compat;


/** Sodium RenderSectionManager上下文交换桥，由可选Mixin实现。 */
public interface IESodiumRenderSectionManager {
    /** 复用当前管理器的远景上下文，视距变化或重入时使用新上下文。 */
    SodiumRenderingContext ip_acquireCameraContext(int renderDistance);

    /** 将当前manager渲染列表/任务列表/视距与传入SodiumRenderingContext双向交换。 */
    void ip_swapContext(SodiumRenderingContext context);
}

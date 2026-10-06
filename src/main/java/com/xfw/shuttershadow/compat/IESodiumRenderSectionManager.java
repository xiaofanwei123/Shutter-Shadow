package com.xfw.shuttershadow.compat;


/** Sodium RenderSectionManager上下文交换桥，由可选Mixin实现。 */
public interface IESodiumRenderSectionManager {
    /** 将当前manager渲染列表/任务列表/视距与传入SodiumRenderingContext双向交换。 */
    void ip_swapContext(SodiumRenderingContext context);
}

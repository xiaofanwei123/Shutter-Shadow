package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import com.xfw.shuttershadow.access.IERenderSection;

/** 提供远景网格池复用渲染区段所需的内部访问接口。 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class MixinRenderSection implements IERenderSection {
    
    private long portal_mark;
    
    /** Shadow 引用原 reset，供完整网格重置桥调用。 */
    @Shadow
    protected abstract void reset();
    
    @Shadow
    @Final
    @Mutable
    public int index;
    
    /** 执行原 reset，清除旧编译状态和任务，以供网格重新分配到另一坐标。 */
    @Override
    public void portal_fullyReset() {
        reset();
    }
    
    /** 读取本模组 portal_mark，地形发现用其记录访问/代次。 */
    @Override
    public long portal_getMark() {
        return portal_mark;
    }
    
    /** 写入 portal_mark，不改变网格世界坐标。 */
    @Override
    public void portal_setMark(long arg) {
        portal_mark = arg;
    }
    
    /** 写入原 RenderSection.index，池分配/重组时更新索引。 */
    @Override
    public void portal_setIndex(int arg) {
        index = arg;
    }
    
}

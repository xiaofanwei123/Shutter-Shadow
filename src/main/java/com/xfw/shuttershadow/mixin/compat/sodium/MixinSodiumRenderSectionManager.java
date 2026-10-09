package com.xfw.shuttershadow.mixin.compat.sodium;


import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import com.xfw.shuttershadow.compat.IESodiumRenderSectionManager;
import com.xfw.shuttershadow.compat.SodiumRenderingContext;

import java.util.ArrayDeque;
import java.util.Map;

/** 为 Sodium 区段管理器提供相机渲染上下文交换能力。 */
@Mixin(value = RenderSectionManager.class, remap = false)
// 相机视图独立保存列表、收集器及其任务队列；编译器和世界区段仍共用。
public class MixinSodiumRenderSectionManager implements IESodiumRenderSectionManager {
    @Shadow
    @Final
    @Mutable
    private int renderDistance;
    
    @Shadow
    private @NotNull SortedRenderLists renderLists;

    @Shadow private SectionCollector sectionCollector;
    @Shadow private SectionCollector lastSectionCollector;
    @Shadow private Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists;

    // 跟随管理器释放，避免全局缓存留住旧世界或已释放的 GPU 区域。
    @Unique private SodiumRenderingContext shuttershadow$cameraContext;

    /** 复用停用的相机上下文，视距变化时替换，重入时保持原上下文独立。 */
    @Override
    public SodiumRenderingContext ip_acquireCameraContext(int renderDistance) {
        if (shuttershadow$cameraContext != null && shuttershadow$cameraContext.active) {
            return new SodiumRenderingContext(renderDistance);
        }
        if (shuttershadow$cameraContext == null
            || shuttershadow$cameraContext.renderDistance != renderDistance) {
            shuttershadow$cameraContext = new SodiumRenderingContext(renderDistance);
        }
        return shuttershadow$cameraContext;
    }
    
    /** 校验并交换视距、可见区段、收集器及任务队列。 */
    @Override
    public void ip_swapContext(SodiumRenderingContext context) {
        Validate.isTrue(context.renderDistance != 0, "Render distance cannot be 0");
        Validate.isTrue(context.renderLists != null);
        
        SortedRenderLists renderListsTmp = renderLists;
        renderLists = context.renderLists;
        context.renderLists = renderListsTmp;

        SectionCollector collectorTmp = sectionCollector;
        sectionCollector = context.sectionCollector;
        context.sectionCollector = collectorTmp;

        collectorTmp = lastSectionCollector;
        lastSectionCollector = context.lastSectionCollector;
        context.lastSectionCollector = collectorTmp;

        var taskListsTmp = taskLists;
        taskLists = context.taskLists;
        context.taskLists = taskListsTmp;
        
        int renderDistanceTmp = renderDistance;
        renderDistance = context.renderDistance;
        context.renderDistance = renderDistanceTmp;
    }
    
}

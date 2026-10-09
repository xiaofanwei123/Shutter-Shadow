package com.xfw.dimensionalexposure.compat;


import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;

/** 保存同一 Sodium 管理器可复用的相机视距、可见区段和任务队列。 */
public class SodiumRenderingContext {
    public RenderSectionManager owner;
    public boolean active;
    public SortedRenderLists renderLists;
    public SectionCollector sectionCollector;
    public SectionCollector lastSectionCollector;
    public Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists = new EnumMap<>(TaskQueueType.class);
    
    public int renderDistance;
    
    /** 保存视距、初始化空SortedRenderLists，并为每种任务类型创建独立ArrayDeque。 */
    public SodiumRenderingContext(int renderDistance) {
        this.renderDistance = renderDistance;
        this.renderLists = SortedRenderLists.empty();
        for (TaskQueueType type : TaskQueueType.values()) {
            taskLists.put(type, new ArrayDeque<>());
        }
    }
}

package com.xfw.shuttershadow.compat;
// Shuttershadow phase seven: relocated into the camera core.

import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;

public class SodiumRenderingContext {
    public RenderSectionManager owner;
    public SortedRenderLists renderLists;
    public SectionCollector sectionCollector;
    public SectionCollector lastSectionCollector;
    public Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists = new EnumMap<>(TaskQueueType.class);
    
    public int renderDistance;
    
    public SodiumRenderingContext(int renderDistance) {
        this.renderDistance = renderDistance;
        this.renderLists = SortedRenderLists.empty();
        for (TaskQueueType type : TaskQueueType.values()) {
            taskLists.put(type, new ArrayDeque<>());
        }
    }
}

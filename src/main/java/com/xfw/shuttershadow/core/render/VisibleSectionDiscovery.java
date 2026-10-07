package com.xfw.shuttershadow.core.render;


import com.xfw.shuttershadow.event.ClientCleanupEvent;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;
import com.xfw.shuttershadow.core.chunk_loading.PerformanceLevel;
import com.xfw.shuttershadow.access.IERenderSection;
import com.xfw.shuttershadow.core.ClientPerformanceMonitor;

import java.util.ArrayDeque;
import java.util.Stack;

/** 同步收集相机远景中可见的原版渲染区段。 */
@OnlyIn(Dist.CLIENT)
public class VisibleSectionDiscovery {
    
    private static RemoteViewArea builtChunks;
    private static Frustum vanillaFrustum;
    private static ObjectArrayList<RenderSection> resultHolder;
    private static final ArrayDeque<RenderSection> tempQueue = new ArrayDeque<>();
    private static SectionPos cameraSectionPos;
    private static long timeMark;
    private static int viewDistance;

    /** 扩展镜头附近的视锥范围，并收集可见渲染区段。 */
    public static void setupTerrain(ClientLevel world, RemoteViewArea storage, Camera camera,
                                    Frustum frustum, ObjectArrayList<RenderSection> sections,
                                    String profilerSection) {
        world.getProfiler().push(profilerSection);
        try {
            discoverVisibleSections(world, storage, camera,
                    new Frustum(frustum).offsetToFullyIncludeCameraCube(8), sections);
        } finally {
            world.getProfiler().pop();
        }
    }
    
    /** 从镜头附近区段开始广度优先搜索，收集视距内的可见区段。 */
    public static void discoverVisibleSections(
        ClientLevel world,
        RemoteViewArea builtChunks_,
        Camera camera,
        Frustum vanillaFrustum_,
        ObjectArrayList<RenderSection> resultHolder_
    ) {
        builtChunks = builtChunks_;
        vanillaFrustum = vanillaFrustum_;
        resultHolder = resultHolder_;
        
        resultHolder.clear();
        tempQueue.clear();
        
        updateViewDistance();
        
        timeMark = System.nanoTime();
        
        Vec3 cameraPos = camera.getPosition();
        vanillaFrustum.prepare(cameraPos.x, cameraPos.y, cameraPos.z);
        cameraSectionPos = SectionPos.of(BlockPos.containing(cameraPos));
        
        if (cameraPos.y < world.getMinBuildHeight()) {
            discoverBottomOrTopLayerVisibleChunks(builtChunks.minSectionY);
        }
        else if (cameraPos.y > world.getMaxBuildHeight()) {
            discoverBottomOrTopLayerVisibleChunks(builtChunks.endSectionY - 1);
        }
        else {
            checkSection(
                cameraSectionPos.x(),
                cameraSectionPos.y(),
                cameraSectionPos.z(),
                true
            );
        }
        
        // 广度优先搜索可见区段。
        while (!tempQueue.isEmpty()) {
            RenderSection curr = tempQueue.poll();
            int cx = SectionPos.blockToSectionCoord(curr.getOrigin().getX());
            int cy = SectionPos.blockToSectionCoord(curr.getOrigin().getY());
            int cz = SectionPos.blockToSectionCoord(curr.getOrigin().getZ());
            
            checkSection(cx + 1, cy, cz, false);
            checkSection(cx - 1, cy, cz, false);
            checkSection(cx, cy + 1, cz, false);
            checkSection(cx, cy - 1, cz, false);
            checkSection(cx, cy, cz + 1, false);
            checkSection(cx, cy, cz - 1, false);
        }
        
        // 清空临时引用，避免对象长期滞留。
        resultHolder = null;
        builtChunks = null;
        vanillaFrustum = null;
    }
    
    /** 根据当前任务视距和性能档位计算有效渲染距离。 */
    private static void updateViewDistance() {
        int distance = WorldRenderInfo.getRenderDistance();
        viewDistance = PerformanceLevel.getCameraRenderDistance(ClientPerformanceMonitor.level, distance);
    }
    
    // 原版视锥剔除可能错误剔除搜索起始区段。
    /** 根据区段包围盒判断是否位于视锥内。 */
    private static boolean isVisible(RenderSection builtChunk) {
        AABB box = builtChunk.getBoundingBox();
        return vanillaFrustum.isVisible(box);
    }
    
    /** 镜头超出世界高度时，从最近的顶部或底部区段开始搜索。 */
    private static void discoverBottomOrTopLayerVisibleChunks(int cy) {
        int centerX = cameraSectionPos.x();
        int centerZ = cameraSectionPos.z();
        int range = viewDistance - 1;
        checkSection(centerX, cy, centerZ, false);
        for (int layer = 1; layer < range; layer++) {
            for (int w = 0; w < layer * 2; w++) {
                checkSection(layer + centerX, cy, w + 1 - layer + centerZ, false);
            }
            for (int w = 0; w < layer * 2; w++) {
                checkSection(-w + layer - 1 + centerX, cy, layer + centerZ, false);
            }
            for (int w = 0; w < layer * 2; w++) {
                checkSection(-layer + centerX, cy, -w + layer - 1 + centerZ, false);
            }
            for (int w = 0; w < layer * 2; w++) {
                checkSection(w + 1 - layer + centerX, cy, -layer + centerZ, false);
            }
        }
    }
    
    /** 各轴距离超视距则返回。 */
    private static void checkSection(int cx, int cy, int cz, boolean skipFrustumTest) {
        if (Math.abs(cx - cameraSectionPos.x()) > viewDistance) {
            return;
        }
        if (Math.abs(cy - cameraSectionPos.y()) > viewDistance) {
            return;
        }
        if (Math.abs(cz - cameraSectionPos.z()) > viewDistance) {
            return;
        }
        
        RenderSection builtChunk =
            builtChunks.rawFetch(cx, cy, cz, timeMark);
        if (builtChunk != null) {
            IERenderSection ieRenderSection = (IERenderSection) builtChunk;
            if (ieRenderSection.portal_getMark() != timeMark) {
                ieRenderSection.portal_setMark(timeMark);// 标记此区段已检查。
                if (skipFrustumTest || isVisible(builtChunk)) {
                    tempQueue.add(builtChunk);
                    resultHolder.add(builtChunk);
                }
            }
        }
    }
    
    private static final Stack<ObjectArrayList<RenderSection>> listCaches = new Stack<>();
    
    /** 从缓存池取得或创建空的区段列表。 */
    public static ObjectArrayList<RenderSection> takeList() {
        if (listCaches.isEmpty()) {
            return new ObjectArrayList<>();
        }
        else {
            return listCaches.pop();
        }
    }
    
    /** 清空使用过的列表并回池。 */
    public static void returnList(ObjectArrayList<RenderSection> list) {
        list.clear();// 清空临时引用，避免对象长期滞留。
        listCaches.push(list);
    }
    
    /** 注册客户端清理时释放列表缓存的回调。 */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, e -> VisibleSectionDiscovery.cleanUp());

    }
    
    /** 清列表池与遍历临时对象引用。 */
    private static void cleanUp() {
        listCaches.clear();
        resultHolder = null;
        builtChunks = null;
        vanillaFrustum = null;
    }
    
}

package com.xfw.shuttershadow.core.render;
// Shuttershadow phase seven: relocated into the camera core.

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
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

import java.util.ArrayDeque;
import java.util.Stack;

/**
 * Discover visible sections by breadth-first traverse, for remote camera rendering.
 * Probably faster than vanilla (because no cave culling and garbage object allocation).
 * No multi-threading because remote camera views are very dynamic which is not suitable for that.
 * No cave culling because vanilla has a multithreaded cave culling that's hard to integrate with remote views.
 */
@OnlyIn(Dist.CLIENT)
public class VisibleSectionDiscovery {
    
    private static RemoteViewArea builtChunks;
    private static Frustum vanillaFrustum;
    private static ObjectArrayList<RenderSection> resultHolder;
    private static final ArrayDeque<RenderSection> tempQueue = new ArrayDeque<>();
    private static SectionPos cameraSectionPos;
    private static long timeMark;
    private static int viewDistance;

    /** 远景、换维首帧和调试视图共用同一地形设置入口。 */
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
        
        // breadth-first searching
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
        
        // avoid memory leak
        resultHolder = null;
        builtChunks = null;
        vanillaFrustum = null;
    }
    
    private static void updateViewDistance() {
        int distance = WorldRenderInfo.getRenderDistance();
        viewDistance = PerformanceLevel.getCameraRenderDistance(ClientPerformanceMonitor.level, distance);
    }
    
    // NOTE the vanilla frustum culling code may wrongly cull the first section
    private static boolean isVisible(RenderSection builtChunk) {
        AABB box = builtChunk.getBoundingBox();
        return vanillaFrustum.isVisible(box);
    }
    
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
                ieRenderSection.portal_setMark(timeMark);// mark it checked
                if (skipFrustumTest || isVisible(builtChunk)) {
                    tempQueue.add(builtChunk);
                    resultHolder.add(builtChunk);
                }
            }
        }
    }
    
    private static final Stack<ObjectArrayList<RenderSection>> listCaches = new Stack<>();
    
    public static ObjectArrayList<RenderSection> takeList() {
        if (listCaches.isEmpty()) {
            return new ObjectArrayList<>();
        }
        else {
            return listCaches.pop();
        }
    }
    
    public static void returnList(ObjectArrayList<RenderSection> list) {
        list.clear();// avoid memory leak
        listCaches.push(list);
    }
    
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, e -> VisibleSectionDiscovery.cleanUp());

    }
    
    private static void cleanUp() {
        listCaches.clear();
        resultHolder = null;
        builtChunks = null;
        vanillaFrustum = null;
    }
    
}

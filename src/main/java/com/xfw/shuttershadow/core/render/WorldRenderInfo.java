package com.xfw.shuttershadow.core.render;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import com.xfw.shuttershadow.access.IECamera;

import java.util.Stack;

/**
 * A world rendering task.
 */
public class WorldRenderInfo {
    
    /**
     * The dimension that it's going to render
     */
    public final ClientLevel world;
    
    /**
     * Camera position
     */
    public final Vec3 cameraPos;
    
    /**
     * Render distance.
     * It cannot render the chunks that are not synced to client.
     */
    public final int renderDistance;
    
    private static final Stack<WorldRenderInfo> renderInfoStack = new Stack<>();
    
    /** 相机远景始终关闭手部和视角摇晃，只保存每次绘制会变化的数据。 */
    public WorldRenderInfo(
        ClientLevel world, Vec3 cameraPos,
        int renderDistance
    ) {
        Validate.notNull(world);
        Validate.notNull(cameraPos);
        this.world = world;
        this.cameraPos = cameraPos;
        this.renderDistance = renderDistance;
    }
    
    public static void pushRenderInfo(WorldRenderInfo worldRenderInfo) {
        renderInfoStack.push(worldRenderInfo);
    }
    
    public static void popRenderInfo() {
        renderInfoStack.pop();
    }
    
    public static void adjustCameraPos(Camera camera) {
        if (!renderInfoStack.isEmpty()) {
            WorldRenderInfo currWorldRenderInfo = getTopRenderInfo();
            ((IECamera) camera).portal_setPos(currWorldRenderInfo.cameraPos);
        }
    }
    
    /** Whether a remote camera world is currently being rendered. */
    public static boolean isRendering() {
        return !renderInfoStack.empty();
    }
    
    public static int getRenderDistance() {
        if (renderInfoStack.isEmpty()) {
            return Minecraft.getInstance().options.getEffectiveRenderDistance();
        }
        
        return getTopRenderInfo().renderDistance;
    }
    
    public static WorldRenderInfo getTopRenderInfo() {
        return renderInfoStack.peek();
    }
    
    public static Vec3 getCameraPos() {
        Validate.isTrue(!renderInfoStack.isEmpty());
        return getTopRenderInfo().cameraPos;
    }
    
}

package com.xfw.shuttershadow.core.render;


import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import com.xfw.shuttershadow.access.IECamera;

import java.util.Stack;

/** 保存远程渲染任务堆栈中的目标世界、镜头位置与视距。 */
public class WorldRenderInfo {
    
    /** 本次需要渲染的客户端世界。 */
    public final ClientLevel world;
    
    /** 本次渲染使用的相机位置。 */
    public final Vec3 cameraPos;
    
    /** 本次渲染视距；未同步到客户端的区块无法渲染。 */
    public final int renderDistance;
    
    private static final Stack<WorldRenderInfo> renderInfoStack = new Stack<>();
    
    /** 校验并保存目标世界、镜头位置和视距。 */
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
    
    /** 将目标渲染任务压入堆栈。 */
    public static void pushRenderInfo(WorldRenderInfo worldRenderInfo) {
        renderInfoStack.push(worldRenderInfo);
    }
    
    /** 弹出当前渲染任务。 */
    public static void popRenderInfo() {
        renderInfoStack.pop();
    }
    
    /** 按当前远程渲染任务调整相机位置。 */
    public static void adjustCameraPos(Camera camera) {
        if (!renderInfoStack.isEmpty()) {
            WorldRenderInfo currWorldRenderInfo = getTopRenderInfo();
            ((IECamera) camera).portal_setPos(currWorldRenderInfo.cameraPos);
        }
    }
    
    /** 返回堆栈是否非空。 */
    public static boolean isRendering() {
        return !renderInfoStack.empty();
    }
    
    /** 目标渲染取栈顶视距，正常渲染取原版有效视距。 */
    public static int getRenderDistance() {
        if (renderInfoStack.isEmpty()) {
            return Minecraft.getInstance().options.getEffectiveRenderDistance();
        }
        
        return getTopRenderInfo().renderDistance;
    }
    
    /** 取得栈顶渲染任务，空栈时抛出异常。 */
    public static WorldRenderInfo getTopRenderInfo() {
        return renderInfoStack.peek();
    }
    
    /** 要求非空并返回栈顶相机位置。 */
    public static Vec3 getCameraPos() {
        Validate.isTrue(!renderInfoStack.isEmpty());
        return getTopRenderInfo().cameraPos;
    }
    
}

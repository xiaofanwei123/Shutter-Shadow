package com.xfw.dimensionalexposure.core.render;


import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import com.xfw.dimensionalexposure.access.IECamera;

import java.util.ArrayDeque;
import java.util.Objects;

/** 保存远程渲染任务堆栈中的目标世界、镜头位置与视距。 */
public class WorldRenderInfo {
    
    /** 本次需要渲染的客户端世界。 */
    public final ClientLevel world;
    
    /** 本次渲染使用的相机位置。 */
    public final Vec3 cameraPos;
    public final Vec3 cameraDirection;
    
    /** 本次渲染视距；未同步到客户端的区块无法渲染。 */
    public final int renderDistance;
    
    private static final ArrayDeque<WorldRenderInfo> renderInfoStack = new ArrayDeque<>();
    
    /** 校验并保存目标世界、镜头位置和视距。 */
    public WorldRenderInfo(
        ClientLevel world, Vec3 cameraPos, Vec3 cameraDirection,
        int renderDistance
    ) {
        this.world = Objects.requireNonNull(world);
        this.cameraPos = Objects.requireNonNull(cameraPos);
        this.cameraDirection = Objects.requireNonNull(cameraDirection).normalize();
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
        WorldRenderInfo current = renderInfoStack.peek();
        if (current != null) {
            IECamera access = (IECamera) camera;
            access.portal_setPos(current.cameraPos);
            Vec3 direction = current.cameraDirection;
            access.ip_setRotation((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                    (float) Math.toDegrees(Math.asin(-direction.y)));
        }
    }
    
    /** 返回堆栈是否非空。 */
    public static boolean isRendering() {
        return !renderInfoStack.isEmpty();
    }
    
    /** 目标渲染取栈顶视距，正常渲染取原版有效视距。 */
    public static int getRenderDistance() {
        WorldRenderInfo current = renderInfoStack.peek();
        return current == null ? Minecraft.getInstance().options.getEffectiveRenderDistance() : current.renderDistance;
    }
    
    /** 取得栈顶渲染任务，空栈时抛出异常。 */
    public static WorldRenderInfo getTopRenderInfo() {
        return renderInfoStack.element();
    }
    
    /** 要求非空并返回栈顶相机位置。 */
    public static Vec3 getCameraPos() {
        return getTopRenderInfo().cameraPos;
    }
    
}

package com.xfw.shuttershadow.core.render;


import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.joml.Matrix4f;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.core.VanillaRuntimeHooks;


/** 目标场景帧缓冲/雾填充、远光照更新及GPU上传时序辅助。 */
public class MyRenderHelper {
    
    public static final Minecraft client = Minecraft.getInstance();

    /** 在相机远景中用目标维度雾色清空画面，并恢复深度写入状态。 */
    public static boolean replaceFrameBufferClearing() {
        if (!WorldRenderInfo.isRendering()) {
            return false;
        }
        RenderSystem.depthMask(false);
        renderScreenTriangle(FogRendererContext.getCurrentFogColor.get());
        RenderSystem.depthMask(true);
        return true;
    }

    // 原版将着色器命名空间固定为游戏自身的命名空间。
    /** 将浮点颜色转换为整数颜色，绘制全屏背景。 */
    public static void renderScreenTriangle(Vec3 color) {
        renderScreenTriangle(
            (int) (color.x * 255),
            (int) (color.y * 255),
            (int) (color.z * 255),
            255
        );
    }
    /** 使用指定颜色绘制全屏背景，并清理着色器状态。 */
    @VanillaRuntimeHooks
    public static void renderScreenTriangle(int r, int g, int b, int a) {
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        Validate.notNull(shader);

        Matrix4f identityMatrix = new Matrix4f();
        identityMatrix.identity();
        
        shader.MODEL_VIEW_MATRIX.set(identityMatrix);
        shader.PROJECTION_MATRIX.set(identityMatrix);
        
        shader.apply();
        
        Tesselator tessellator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferBuilder = tessellator.
            begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        
        bufferBuilder.addVertex(1, -1, 0).setColor(r, g, b, a);
        bufferBuilder.addVertex(1, 1, 0).setColor(r, g, b, a);
        bufferBuilder.addVertex(-1, 1, 0).setColor(r, g, b, a);
        
        bufferBuilder.addVertex(-1, 1, 0).setColor(r, g, b, a);
        bufferBuilder.addVertex(-1, -1, 0).setColor(r, g, b, a);
        bufferBuilder.addVertex(1, -1, 0).setColor(r, g, b, a);
        
        BufferUploader.draw(bufferBuilder.build());
        
        shader.clear();
    }
    
    // 清除已标记移除的光照区段，
    // 避免光照数据残留造成内存泄漏，
    // 也避免区块重新加载后误删其光照数据。
    // 必须在世界渲染后执行，以免区段边界的平滑光照异常。
    /** 多世界初始化后运行非真实当前维度的光照引擎更新。 */
    public static void lateUpdateLight() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }
        
        ClientWorldLoader.getClientWorlds().forEach(world -> {
            if (!RenderStates.isDimensionRendered(world.dimension())) {
                world.getChunkSource().getLightEngine().runLightUpdates();
            }
        });
    }
    
    /** 提前上传远程世界待处理的网格，减少首张照片缺少地形。 */
    public static void earlyRemoteUpload() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }
        
        ClientWorldLoader.WORLD_RENDERER_MAP.forEach((dim, worldRenderer) -> {
            if (client.level.dimension() != dim) {
                worldRenderer.getSectionRenderDispatcher().uploadAllPendingUploads();
            }
        });
    }
}

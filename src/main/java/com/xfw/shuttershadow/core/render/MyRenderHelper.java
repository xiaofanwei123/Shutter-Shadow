package com.xfw.shuttershadow.core.render;
// Shuttershadow phase seven: relocated into the camera core.

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
import com.xfw.shuttershadow.core.render.RenderStates;


import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.glCullFace;

public class MyRenderHelper {
    
    public static final Minecraft client = Minecraft.getInstance();

    /** 相机远景用维度雾色填满当前目标，原版画面保留原清屏方式。 */
    public static boolean replaceFrameBufferClearing() {
        if (!WorldRenderInfo.isRendering()) {
            return false;
        }
        RenderSystem.depthMask(false);
        renderScreenTriangle(FogRendererContext.getCurrentFogColor.get());
        RenderSystem.depthMask(true);
        return true;
    }
    
    
    
    // vanilla hardcodes the shader namespace to be "minecraft"
    
    
    
    
    public static void renderScreenTriangle(Vec3 color) {
        renderScreenTriangle(
            (int) (color.x * 255),
            (int) (color.y * 255),
            (int) (color.z * 255),
            255
        );
    }
    
    
    /**
     * {@link RenderTarget#blitToScreen(int, int)}
     */
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
    
    /**
     * {@link RenderTarget#blitToScreen(int, int)}
     */


    
    
    /**
     * {@link RenderTarget#blitToScreen(int, int)}
     */
    
    // it will remove the light sections that are marked to be removed
    // if not, light data will cause minor memory leak
    // and wrongly remove the light data when the chunks get reloaded to client
    // this should not run before world rendering or the smooth lighting may become abnormal in section edge
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
    
    /**
     * If we don't do this
     * the future created in {@link SectionRenderDispatcher#uploadSectionLayer}
     * may never complete
     */
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
    
    public static void recoverFaceCulling() {
        glCullFace(GL_BACK);
    }
    
    
    
    
}

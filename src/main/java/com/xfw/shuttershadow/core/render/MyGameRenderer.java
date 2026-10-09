package com.xfw.shuttershadow.core.render;


import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import com.xfw.shuttershadow.util.CHelper;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.compat.IrisInterface;
import com.xfw.shuttershadow.compat.SodiumInterface;
import com.xfw.shuttershadow.access.IEGameRenderer;
import com.xfw.shuttershadow.access.IEMinecraftClient;
import com.xfw.shuttershadow.access.IEParticleManager;
import com.xfw.shuttershadow.access.IEWorldRenderer;
import com.xfw.shuttershadow.mixin.minecraft.client.IERenderSystem;
import com.xfw.shuttershadow.mixin.minecraft.client.IESectionRenderDispatcher;
import java.util.ArrayDeque;

// 仅供客户端使用。
/** 目标世界渲染的状态保存/恢复中心。 */
public class MyGameRenderer {
    public static final Minecraft client = Minecraft.getInstance();
    
    // 远程维度与玩家世界使用独立的渲染缓冲存储。
    private static final ArrayDeque<RenderBuffers> secondaryRenderBuffers = new ArrayDeque<>();
    
    // 原版可见区段发现采用多线程，
    // 跨维度传送后的第一帧可能无法及时得到正确结果，
    // 因此首帧使用内核的同步可见区段算法。
    public static int vanillaTerrainSetupOverride = 0;
    
    /** 登记目标渲染任务，完成绘制后恢复渲染任务堆栈。 */
    public static void renderWorldNew(WorldRenderInfo worldRenderInfo) {
        WorldRenderInfo.pushRenderInfo(worldRenderInfo);
        try {
            switchAndRenderTheWorld(
                worldRenderInfo.world,
                worldRenderInfo.renderDistance
            );
        } finally {
            WorldRenderInfo.popRenderInfo();
        }
    }
    
    /** 暂存原世界渲染状态，切换目标世界完成绘制后恢复。 */
    private static void switchAndRenderTheWorld(
        ClientLevel newWorld,
        int renderDistance
    ) {
        ResourceKey<Level> newDimension = newWorld.dimension();
        
        LevelRenderer worldRenderer = ClientWorldLoader.getWorldRenderer(newDimension);
        
        CHelper.checkGlError();
        
        IEGameRenderer ieGameRenderer = (IEGameRenderer) client.gameRenderer;
        DimensionRenderHelper helper =
            ClientWorldLoader.getDimensionRenderHelper(newDimension);
        Camera newCamera = new Camera();
        
        // 保存当前世界的渲染状态。
        ClientLevel oldWorld = client.level;
        LevelRenderer oldWorldRenderer = client.levelRenderer;
        LightTexture oldLightmap = client.gameRenderer.lightTexture();
        boolean oldNoClip = client.player.noPhysics;
        boolean oldDoRenderHand = ieGameRenderer.ip_getDoRenderHand();
        ObjectArrayList<SectionRenderDispatcher.RenderSection> oldChunkInfoList =
            ((IEWorldRenderer) oldWorldRenderer).portal_getChunkInfoList();
        HitResult oldCrosshairTarget = client.hitResult;
        Camera oldCamera = client.gameRenderer.getMainCamera();
        PostChain oldTransparencyShader = ((IEWorldRenderer) worldRenderer).portal_getTransparencyShader();
        RenderBuffers oldRenderBuffers = ((IEWorldRenderer) worldRenderer).ip_getRenderBuffers();
        RenderBuffers oldClientRenderBuffers = client.renderBuffers();
        SectionBufferBuilderPack oldSectionRenderDispatcherFixedBuffers =
            ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
                .ip_getFixedBuffers();
        Frustum oldFrustum = ((IEWorldRenderer) worldRenderer).portal_getFrustum();
        
        // 投影矩阵包含视角摇晃，
        // 摇晃效果会受到视图缩放的影响。
        Matrix4f oldProjectionMatrix = RenderSystem.getProjectionMatrix();
        Matrix4fStack oldModelViewStack = RenderSystem.getModelViewStack();
        
        Object irisPipeline = IrisInterface.invoker.getPipeline(worldRenderer);
        ObjectArrayList<SectionRenderDispatcher.RenderSection> newChunkInfoList =
            VisibleSectionDiscovery.takeList();

        RenderBuffers newRenderBuffers = null;
        Object newSodiumContext = null;
        boolean fogContextSwapped = false;
        boolean sodiumContextSwapped = false;
        // 任一渲染或兼容钩子失败时，也必须恢复玩家世界和已完成的切换。
        try {
            ((IEWorldRenderer) oldWorldRenderer).portal_setChunkInfoList(newChunkInfoList);

            // 切换渲染上下文，同时保留客户端玩家所在的世界。
            ((IEMinecraftClient) client).ip_setWorldRenderer(worldRenderer);
            client.level = newWorld;
            ieGameRenderer.ip_setLightmapTextureManager(helper.lightmapTexture);

            client.getBlockEntityRenderDispatcher().level = newWorld;
            client.player.noPhysics = true;
            client.gameRenderer.setRenderHand(false);

            FogRendererContext.push(newDimension);
            fogContextSwapped = true;
            ((IEParticleManager) client.particleEngine).ip_setWorld(newWorld);
            ieGameRenderer.ip_setCamera(newCamera);

            newRenderBuffers = secondaryRenderBuffers.pollLast();
            if (newRenderBuffers == null) newRenderBuffers = new RenderBuffers(0);
            ((IEWorldRenderer) worldRenderer).ip_setRenderBuffers(newRenderBuffers);
            ((IEMinecraftClient) client).ip_setRenderBuffers(newRenderBuffers);

            /* 原版实体缓冲可能尚未结束构建；远景若复用此缓冲并在实体渲染后重建区块，会触发重复构建错误。 */
            ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
                .ip_setFixedBuffers(newRenderBuffers.fixedBufferPack());

            newSodiumContext = SodiumInterface.invoker.acquireContext(renderDistance);
            SodiumInterface.invoker.switchContextWithCurrentWorldRenderer(newSodiumContext);
            sodiumContextSwapped = true;

            ((IEWorldRenderer) worldRenderer).portal_setTransparencyShader(null);

            IERenderSystem.ip_setModelViewStack(new Matrix4fStack(16));
            RenderSystem.applyModelViewMatrix();

            IrisInterface.invoker.setPipeline(worldRenderer, null);

            // 更新目标世界的光照纹理。
            if (!RenderStates.isDimensionRendered(newDimension)) {
                helper.lightmapTexture.updateLightTexture(0);
            }

            // 执行目标世界渲染。
            client.getProfiler().push("render_portal_content");
            try {
                client.gameRenderer.renderLevel(client.getTimer());
            } finally {
                client.getProfiler().pop();
            }

        } finally {
            try {
                if (sodiumContextSwapped) {
                    SodiumInterface.invoker.switchContextWithCurrentWorldRenderer(newSodiumContext);
                }
            } finally {

                // 恢复玩家世界的渲染状态。

                ((IEMinecraftClient) client).ip_setWorldRenderer(oldWorldRenderer);
                client.level = oldWorld;
                ieGameRenderer.ip_setLightmapTextureManager(oldLightmap);
                client.getBlockEntityRenderDispatcher().level = oldWorld;
                client.player.noPhysics = oldNoClip;
                client.gameRenderer.setRenderHand(oldDoRenderHand);

                ((IEParticleManager) client.particleEngine).ip_setWorld(oldWorld);
                client.hitResult = oldCrosshairTarget;
                ieGameRenderer.ip_setCamera(oldCamera);

                ((IEWorldRenderer) worldRenderer).portal_setTransparencyShader(oldTransparencyShader);

                if (fogContextSwapped) FogRendererContext.pop();

                ((IEWorldRenderer) oldWorldRenderer).portal_setChunkInfoList(oldChunkInfoList);
                VisibleSectionDiscovery.returnList(newChunkInfoList);

                ((IEWorldRenderer) worldRenderer).ip_setRenderBuffers(oldRenderBuffers);
                ((IEMinecraftClient) client).ip_setRenderBuffers(oldClientRenderBuffers);
                ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
                    .ip_setFixedBuffers(oldSectionRenderDispatcherFixedBuffers);
                if (newRenderBuffers != null) {
                    secondaryRenderBuffers.addLast(newRenderBuffers);
                }

                ((IEWorldRenderer) worldRenderer).portal_setFrustum(oldFrustum);

                client.gameRenderer.resetProjectionMatrix(oldProjectionMatrix);
                IERenderSystem.ip_setModelViewStack(oldModelViewStack);
                RenderSystem.applyModelViewMatrix();

                IrisInterface.invoker.setPipeline(worldRenderer, irisPipeline);

                client.getEntityRenderDispatcher()
                    .prepare(
                        client.level,
                        oldCamera,
                        client.crosshairPickEntity
                    );

            }
        }
        CHelper.checkGlError();
    }
    
    
}

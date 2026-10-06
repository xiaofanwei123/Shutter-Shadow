package com.xfw.shuttershadow.core.render;
// Shuttershadow phase seven: relocated into the camera core.

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
import com.xfw.shuttershadow.core.render.DimensionRenderHelper;
import com.xfw.shuttershadow.core.render.FogRendererContext;
import com.xfw.shuttershadow.core.render.RenderStates;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

import java.util.Stack;

//@OnlyIn(Dist.CLIENT)
// Shuttershadow 第六轮：保留相机多世界渲染及恢复，撤销门户遮挡和准星分支。
public class MyGameRenderer {
    public static final Minecraft client = Minecraft.getInstance();
    
//    public static final int MAX_SECONDARY_BUFFER_NUM = 2;
    
    // portal rendering and outer world rendering uses different buffer builder storages
    private static Stack<RenderBuffers> secondaryRenderBuffers = new Stack<>();
    
    // the vanilla visibility sections discovery code is multithreaded
    // when the player teleports through a portal, on the first frame it will not work normally
    // so use IP's non-multi-threaded algorithm at the first frame
    public static int vanillaTerrainSetupOverride = 0;
    
    private static RenderBuffers acquireRenderBuffersObject() {
        if (secondaryRenderBuffers.isEmpty()) {
            return new RenderBuffers(0);
        }
        else {
            return secondaryRenderBuffers.pop();
        }
    }
    
    private static void returnRenderBuffersObject(RenderBuffers renderBuffers) {
        secondaryRenderBuffers.push(renderBuffers);
    }
    
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
        
        // store old state
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
        
        // the projection matrix contains view bobbing.
        // the view bobbing is related with scale
        Matrix4f oldProjectionMatrix = RenderSystem.getProjectionMatrix();
        Matrix4fStack oldModelViewStack = IERenderSystem.ip_getModelViewStack();
        
        ObjectArrayList<SectionRenderDispatcher.RenderSection> newChunkInfoList =
            VisibleSectionDiscovery.takeList();
        Object irisPipeline = IrisInterface.invoker.getPipeline(worldRenderer);

        RenderBuffers newRenderBuffers = null;
        Object newSodiumContext = null;
        boolean fogContextSwapped = false;
        boolean sodiumContextSwapped = false;
        // 任一渲染或兼容钩子失败时，也必须恢复玩家世界和已完成的切换。
        try {
            ((IEWorldRenderer) oldWorldRenderer).portal_setChunkInfoList(newChunkInfoList);

            // switch (note: it will no longer switch the world that client player is in )
            ((IEMinecraftClient) client).ip_setWorldRenderer(worldRenderer);
            client.level = newWorld;
            ieGameRenderer.ip_setLightmapTextureManager(helper.lightmapTexture);

            client.getBlockEntityRenderDispatcher().level = newWorld;
            client.player.noPhysics = true;
            client.gameRenderer.setRenderHand(false);

            FogRendererContext.swappingManager.pushSwapping(newDimension);
            fogContextSwapped = true;
            ((IEParticleManager) client.particleEngine).ip_setWorld(newWorld);
            ieGameRenderer.ip_setCamera(newCamera);

            newRenderBuffers = acquireRenderBuffersObject();
            ((IEWorldRenderer) worldRenderer).ip_setRenderBuffers(newRenderBuffers);
            ((IEMinecraftClient) client).ip_setRenderBuffers(newRenderBuffers);

            /*
              the vanilla buffer pack may be used by {@link net.minecraft.client.renderer.MultiBufferSource.BufferSource}
              The BufferSource does not always immediately finish building.
              Reusing that may cause "Already Building" error in Buffer Builder when doing main-thread chunk rebuilding.
              This does not occur in vanilla because vanilla does main-thread chunk rebuilding before entity rendering. With portal rendering it could do chunk rebuilding after some entity rendering.
             */
            ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
                .ip_setFixedBuffers(newRenderBuffers.fixedBufferPack());

            newSodiumContext = SodiumInterface.invoker.createNewContext(renderDistance);
            SodiumInterface.invoker.switchContextWithCurrentWorldRenderer(newSodiumContext);
            sodiumContextSwapped = true;

            ((IEWorldRenderer) worldRenderer).portal_setTransparencyShader(null);

            IERenderSystem.ip_setModelViewStack(new Matrix4fStack(16));
            RenderSystem.applyModelViewMatrix();

            IrisInterface.invoker.setPipeline(worldRenderer, null);

            //update lightmap
            if (!RenderStates.isDimensionRendered(newDimension)) {
                helper.lightmapTexture.updateLightTexture(0);
            }

            //invoke rendering
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

                //recover

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

                if (fogContextSwapped) FogRendererContext.swappingManager.popSwapping();

                ((IEWorldRenderer) oldWorldRenderer).portal_setChunkInfoList(oldChunkInfoList);
                VisibleSectionDiscovery.returnList(newChunkInfoList);

                ((IEWorldRenderer) worldRenderer).ip_setRenderBuffers(oldRenderBuffers);
                ((IEMinecraftClient) client).ip_setRenderBuffers(oldClientRenderBuffers);
                ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
                    .ip_setFixedBuffers(oldSectionRenderDispatcherFixedBuffers);
                if (newRenderBuffers != null) {
                    returnRenderBuffersObject(newRenderBuffers);
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

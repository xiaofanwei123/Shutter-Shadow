package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.compat.IrisInterface;
import com.xfw.shuttershadow.compat.SodiumInterface;
import com.xfw.shuttershadow.access.IEWorldRenderer;
import com.xfw.shuttershadow.core.render.RemoteViewArea;
import com.xfw.shuttershadow.core.render.MyGameRenderer;
import com.xfw.shuttershadow.core.render.MyRenderHelper;
import com.xfw.shuttershadow.core.render.VisibleSectionDiscovery;
import com.xfw.shuttershadow.core.render.RenderStates;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;
import com.xfw.shuttershadow.util.Helper;

@SuppressWarnings("JavadocReference")
@Mixin(value = LevelRenderer.class)
// Shuttershadow 第六轮：仅保留远景相机区块、光照和渲染状态接线。
public abstract class MixinLevelRenderer implements IEWorldRenderer {
    
    @Shadow
    private ClientLevel level;
    
    @Shadow
    @Final
    private EntityRenderDispatcher entityRenderDispatcher;
    
    @Shadow
    private ViewArea viewArea;
    
    @Shadow
    private PostChain transparencyChain;
    
    @Mutable
    @Shadow
    @Final
    private RenderBuffers renderBuffers;
    
    @Shadow
    private Frustum cullingFrustum;
    
    @Shadow
    @Nullable
    private VertexBuffer starBuffer;
    
    @Shadow
    @Nullable
    private VertexBuffer skyBuffer;
    
    @Shadow
    @Nullable
    private VertexBuffer darkBuffer;
    
    @Shadow
    @Nullable
    private VertexBuffer cloudBuffer;
    
    @Shadow
    protected abstract void deinitTransparency();
    
    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;
    
    @Shadow
    @Final
    @Mutable
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    
    
    
    
    
    
    
    @Inject(
        method = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onSetupTerrainBegin(
        Camera camera, Frustum frustum, boolean hasForcedFrustum, boolean spectator,
        CallbackInfo ci
    ) {
        if (WorldRenderInfo.isRendering()) {
            if (level.dimension() != RenderStates.originalPlayerDimension) {
                sectionRenderDispatcher.setCamera(camera.getPosition());
            }
        }
        
        if (ip_allowOverrideTerrainSetup()) {
            if (WorldRenderInfo.isRendering()) {
                VisibleSectionDiscovery.setupTerrain(
                    level, ((RemoteViewArea) viewArea),
                    camera, frustum, visibleSections, "ip_terrain_setup"
                );
                
                ci.cancel();
            }
        }
    }
    
    private boolean ip_allowOverrideTerrainSetup() {
        return !SodiumInterface.invoker.isSodiumPresent()
            && !IrisInterface.invoker.isRenderingShadowMap();
    }
    
    @Inject(
        method = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
        at = @At("RETURN"),
        cancellable = true
    )
    private void onSetupTerrainEnd(
        Camera camera, Frustum frustum, boolean hasForcedFrustum, boolean spectator,
        CallbackInfo ci
    ) {
        if (!WorldRenderInfo.isRendering()) {
            if (ip_allowOverrideTerrainSetup()) {
                if (MyGameRenderer.vanillaTerrainSetupOverride > 0) {
                    MyGameRenderer.vanillaTerrainSetupOverride--;
                    
                    VisibleSectionDiscovery.setupTerrain(
                        level, ((RemoteViewArea) viewArea),
                        camera, frustum, visibleSections, "ip_terrain_setup"
                    );
                }
            }
        }
    }
    
    @Redirect(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
            remap = false
        )
    )
    private void redirectClearing(int mask, boolean onOsx) {
        if (!MyRenderHelper.replaceFrameBufferClearing()) {
            RenderSystem.clear(mask, onOsx);
        }
    }

    @Redirect(
        method = "allChanged",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;Lnet/minecraft/world/level/Level;ILnet/minecraft/client/renderer/LevelRenderer;)Lnet/minecraft/client/renderer/ViewArea;"
        )
    )
    private ViewArea redirectConstructingBuildChunkStorage(
        SectionRenderDispatcher chunkBuilder_1,
        Level world_1,
        int int_1,
        LevelRenderer worldRenderer_1
    ) {
        return new RemoteViewArea(
            chunkBuilder_1, world_1, int_1, worldRenderer_1
        );
    }
    
    // @Inject does not allow getting the entity reference
    // maybe needs Mixin Extra
    
    
    
    //avoid render glowing entities when rendering portal
    @Redirect(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;shouldEntityAppearGlowing(Lnet/minecraft/world/entity/Entity;)Z"
        )
    )
    private boolean redirectGlowing(Minecraft client, Entity entity) {
        if (WorldRenderInfo.isRendering()) {
            return false;
        }
        return client.shouldEntityAppearGlowing(entity);
    }
    
    // sometimes we change renderDistance but we don't want to reload it
    @Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
    private void onReloadStarted(CallbackInfo ci) {
        if (WorldRenderInfo.isRendering()) {
            Helper.log("world renderer reloading cancelled during portal rendering");
            ci.cancel();
        }
    }
    
    //reload other world renderers when the main world renderer is reloaded
    @Inject(method = "allChanged", at = @At("TAIL"))
    private void onReloadFinished(CallbackInfo ci) {
        LevelRenderer this_ = (LevelRenderer) (Object) this;
        
        if (ClientWorldLoader.getIsCreatingClientWorld()) {
            return;
        }
        
        Validate.isTrue(Minecraft.getInstance().levelRenderer == this_);
        
        ClientWorldLoader._onWorldRendererReloaded();
    }
    
    @Inject(
        method = "renderSky",
        at = @At("RETURN")
    )
    private void onRenderSkyEnd(
        Matrix4f modelView, Matrix4f matrix4f, float f, Camera camera,
        boolean bl, Runnable runnable, CallbackInfo ci
    ) {
        MyRenderHelper.recoverFaceCulling();
    }
    
    // correct the eye position for sky rendering
    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 redirectGetEyePositionInSkyRendering(LocalPlayer player, float partialTicks) {
        if (WorldRenderInfo.isRendering()) {
            return WorldRenderInfo.getCameraPos();
        }
        return player.getEyePosition(partialTicks);
    }
    
    // vanilla clears translucentFramebuffer even when transparencyShader is null
    // it makes the framebuffer to be wrongly bound in fabulous mode
    
    // if not in spectator mode, when the camera is in block chunk culling will cull chunks wrongly
    @ModifyVariable(
        method = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 1
    )
    private boolean modifyIsSpectator(boolean value) {
        if (WorldRenderInfo.isRendering()) {
            return true;
        }
        return value;
    }
    
    // the captured lambda uses the net handler's world field
    // so switch that correctly
    @Redirect(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;pollLightUpdates()V"
        )
    )
    private void redirectRunQueuedChunkUpdates(ClientLevel world) {
        ClientWorldLoader.withSwitchedWorld(
            world, world::pollLightUpdates
        );
    }
    
    /**
     * when rendering portal, it won't call {@link ViewArea#repositionCamera(double, double)}
     * So {@link ViewArea#getRenderSectionAt} will return incorrect result
     */
    
    
    @Override
    public EntityRenderDispatcher ip_getEntityRenderDispatcher() {
        return entityRenderDispatcher;
    }
    
    @Override
    public ViewArea ip_getBuiltChunkStorage() {
        return viewArea;
    }
    
    @Override
    public PostChain portal_getTransparencyShader() {
        return transparencyChain;
    }
    
    @Override
    public void portal_setTransparencyShader(PostChain arg) {
        transparencyChain = arg;
    }
    
    @Override
    public RenderBuffers ip_getRenderBuffers() {
        return renderBuffers;
    }
    
    @Override
    public void ip_setRenderBuffers(RenderBuffers arg) {
        renderBuffers = arg;
    }
    
    @Override
    public Frustum portal_getFrustum() {
        return cullingFrustum;
    }
    
    @Override
    public void portal_setFrustum(Frustum arg) {
        cullingFrustum = arg;
    }
    
    @Override
    public void portal_fullyDispose() {
        deinitTransparency();
        
        if (starBuffer != null) {
            starBuffer.close();
        }
        if (skyBuffer != null) {
            skyBuffer.close();
        }
        if (darkBuffer != null) {
            darkBuffer.close();
        }
        if (cloudBuffer != null) {
            cloudBuffer.close();
        }
        
        level = null;
    }
    
    @Override
    public void portal_setChunkInfoList(ObjectArrayList<SectionRenderDispatcher.RenderSection> arg) {
        visibleSections = arg;
    }
    
    @Override
    public ObjectArrayList<SectionRenderDispatcher.RenderSection> portal_getChunkInfoList() {
        return visibleSections;
    }
}

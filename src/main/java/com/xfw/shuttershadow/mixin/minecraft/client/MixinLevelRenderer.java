package com.xfw.shuttershadow.mixin.minecraft.client;


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
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
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

/** 接入多世界地形、光照、帧缓冲及渲染资源管理。 */
@SuppressWarnings("JavadocReference")
@Mixin(value = LevelRenderer.class)
public abstract class MixinLevelRenderer implements IEWorldRenderer {
    
    @Shadow
    private ClientLevel level;
    
    @Shadow
    private ViewArea viewArea;

    @Shadow
    @Final
    private SectionOcclusionGraph sectionOcclusionGraph;
    
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
    
    /** Shadow 引用原 deinitTransparency，完整销毁渲染器时关闭透明后处理目标。 */
    @Shadow
    protected abstract void deinitTransparency();
    
    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;
    
    @Shadow
    @Final
    @Mutable
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    
    
    
    
    
    
    
    /** setupRender 开头：远维度时先把编译器 camera 设到当前观察位置。 */
    @Inject(
        method = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onSetupTerrainBegin(
        Camera camera, Frustum frustum, boolean hasForcedFrustum, boolean spectator,
        CallbackInfo ci
    ) {
        // 无缝换维复用后台渲染器，首次主视角绘制前补齐普通网格并重置遮挡图。
        if (!WorldRenderInfo.isRendering() && viewArea instanceof RemoteViewArea remoteViewArea) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && remoteViewArea.preparePlayerView(player.getX(), player.getZ())) {
                sectionOcclusionGraph.waitAndReset(viewArea);
            }
        }
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
    
    /** 仅在无 Sodium 且未绘制 Iris 阴影时允许重建原版地形列表。 */
    private boolean ip_allowOverrideTerrainSetup() {
        return !SodiumInterface.invoker.isSodiumPresent()
            && !IrisInterface.invoker.isRenderingShadowMap();
    }
    
    /** 恢复主世界后，在限定次数内刷新原版可见地形列表。 */
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
    
    /** 相机已接管清屏时跳过重复清屏，保留输出帧缓冲。 */
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

    /** 使用远景视图区管理额外区块的渲染网格及释放。 */
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
    
    // 普通 @Inject 无法获取这里的实体引用。
    // 获取局部实体引用可能需要 MixinExtras。
    
    
    
    // 远景渲染期间不绘制实体发光轮廓。
    /** 远景渲染时禁用源世界实体发光轮廓。 */
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
    
    // 临时修改视距时避免触发整个世界渲染器重载。
    /** allChanged 开头：正在额外世界渲染时取消重载，避免绘制中释放当前 ViewArea/缓冲。 */
    @Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
    private void onReloadStarted(CallbackInfo ci) {
        if (WorldRenderInfo.isRendering()) {
            ci.cancel();
        }
    }
    
    // 主世界渲染器重载时同步重载其它世界渲染器。
    /** allChanged 尾部：新建后台世界时不触发全局重载。 */
    @Inject(method = "allChanged", at = @At("TAIL"))
    private void onReloadFinished(CallbackInfo ci) {
        LevelRenderer this_ = (LevelRenderer) (Object) this;
        
        if (ClientWorldLoader.getIsCreatingClientWorld()) {
            return;
        }
        
        Validate.isTrue(Minecraft.getInstance().levelRenderer == this_);
        
        ClientWorldLoader._onWorldRendererReloaded();
    }
    
    // 修正天空渲染使用的观察位置。
    /** 远景天空渲染使用远景相机位置，其余视角沿用玩家眼位。 */
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
    
    // 原版在透明着色器为空时也会清理透明帧缓冲。
    // 这会使极佳画质模式绑定错误的帧缓冲。
    
    // 非旁观模式下，相机位于方块内可能导致区块被错误剔除。
    /** 远景相机按旁观视角处理，避免位于墙内时错误剔除地形。 */
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
    
    // 捕获的回调会读取网络处理器中的世界字段。
    // 因此需要同步切换该字段。
    /** 在所属世界的上下文中处理光照更新，完成后恢复原世界。 */
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
    
    /** 返回 viewArea，供网格发现、重定位和资源释放使用。 */
    @Override
    public ViewArea ip_getBuiltChunkStorage() {
        return viewArea;
    }

    /** 等待旧遮挡任务结束并断开可见区段引用，保留后台区段及其缓冲。 */
    @Override
    public void ip_resetTerrain() {
        sectionOcclusionGraph.waitAndReset(viewArea);
        visibleSections.clear();
    }
    
    /** 读取 transparencyChain，保存对应世界的透明后处理链。 */
    @Override
    public PostChain portal_getTransparencyShader() {
        return transparencyChain;
    }
    
    /** 替换 transparencyChain 引用，供上下文切换或重建管理使用。 */
    @Override
    public void portal_setTransparencyShader(PostChain arg) {
        transparencyChain = arg;
    }
    
    /** 返回该 LevelRenderer 的 RenderBuffers 引用。 */
    @Override
    public RenderBuffers ip_getRenderBuffers() {
        return renderBuffers;
    }
    
    /** 替换 RenderBuffers 引用，供多个世界临时切换绘制缓冲并恢复。 */
    @Override
    public void ip_setRenderBuffers(RenderBuffers arg) {
        renderBuffers = arg;
    }
    
    /** 返回 cullingFrustum 当前引用，供暂存/恢复裁剪视锥。 */
    @Override
    public Frustum portal_getFrustum() {
        return cullingFrustum;
    }
    
    /** 替换 cullingFrustum，供相机视角绘制对应世界可见范围。 */
    @Override
    public void portal_setFrustum(Frustum arg) {
        cullingFrustum = arg;
    }
    
    /** 释放透明后处理及天空、云等顶点缓冲，并断开世界引用。 */
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
    
    /** 替换 visibleSections 引用，地形发现/世界切换时换入该世界当前可见网格列表。 */
    @Override
    public void portal_setChunkInfoList(ObjectArrayList<SectionRenderDispatcher.RenderSection> arg) {
        visibleSections = arg;
    }
    
    /** 返回 visibleSections 实际列表，供地形发现与渲染上下文管理使用。 */
    @Override
    public ObjectArrayList<SectionRenderDispatcher.RenderSection> portal_getChunkInfoList() {
        return visibleSections;
    }
}

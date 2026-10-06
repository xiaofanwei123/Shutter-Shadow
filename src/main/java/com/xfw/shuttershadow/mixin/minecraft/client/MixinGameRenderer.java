package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.core.CoreSettings;
import com.xfw.shuttershadow.access.IEGameRenderer;
import com.xfw.shuttershadow.core.render.MyRenderHelper;
import com.xfw.shuttershadow.core.render.RenderStates;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;
import com.xfw.shuttershadow.client.ImmersiveCameraClient;

@Mixin(GameRenderer.class)
// Shuttershadow 第六轮：撤销物理门渲染与自动穿门，保留相机变换和多世界状态。
public abstract class MixinGameRenderer implements IEGameRenderer {
    @Shadow
    @Final
    @Mutable
    private LightTexture lightTexture;
    
    @Shadow
    private boolean renderHand;
    @Shadow
    @Final
    @Mutable
    private Camera mainCamera;
    
    @Shadow
    @Final
    private Minecraft minecraft;

    /** 实时取景和 Exposure 离屏截图共用远景入口。 */
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$renderImmersiveCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (ImmersiveCameraClient.render(deltaTracker)) ci.cancel();
    }

    // 相机远景渲染期间保留主世界准星目标，避免在临时世界中重算。
    @Inject(method = "Lnet/minecraft/client/renderer/GameRenderer;pick(F)V", at = @At("HEAD"), cancellable = true)
    private void onUpdateTargetedEntity(float partialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            if (WorldRenderInfo.isRendering()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onFarBeforeRendering(
        DeltaTracker deltaTracker, boolean renderWorldIn, CallbackInfo ci
    ) {
        if (minecraft.level == null) {
            return;
        }
        if (!renderWorldIn) { // when respawning, it will runTick and execute rendering
            return;
        }
        minecraft.getProfiler().push("ip_pre_render");
        // Note do not use delta tick. use partial tick.
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        RenderStates.updatePreRenderInfo(partialTick);
        NeoForge.EVENT_BUS.post(new CoreSettings.PreGameRenderEvent());
        MyRenderHelper.earlyRemoteUpload();
        minecraft.getProfiler().pop();
        
    }
    
    //before rendering world (not triggered when rendering portal)
    
    //after rendering world (not triggered when rendering portal)
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterRenderingCenter(
        DeltaTracker deltaTracker, boolean bl, CallbackInfo ci
    ) {
        RenderStates.onTotalRenderEnd();
        
        minecraft.getProfiler().push("ip_late_update_light");
        MyRenderHelper.lateUpdateLight();
        minecraft.getProfiler().pop();
    }
    
    //special rendering in third person view
    
    
    
    //resize all world renderers when resizing window
    @Inject(method = "Lnet/minecraft/client/renderer/GameRenderer;resize(II)V", at = @At("RETURN"))
    private void onOnResized(int int_1, int int_2, CallbackInfo ci) {
        if (ClientWorldLoader.getIsInitialized()) {
            ClientWorldLoader.WORLD_RENDERER_MAP.values().stream()
                .filter(
                    worldRenderer -> worldRenderer != minecraft.levelRenderer
                )
                .forEach(
                    worldRenderer -> worldRenderer.resize(int_1, int_2)
                );
        }
    }
    
    // not using ModifyArgs because ModifyArgs seems broken on Forge
    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 0
    )
    private float modifyBobViewTranslateX(float f) {
        return (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
    }
    
    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 1
    )
    private float modifyBobViewTranslateY(float f) {
        return (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
    }
    
    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 2
    )
    private float modifyBobViewTranslateZ(float f) {
        return (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
    }


    @Override
    public void ip_setLightmapTextureManager(LightTexture manager) {
        lightTexture = manager;
    }
    
    @Override
    public boolean ip_getDoRenderHand() {
        return renderHand;
    }
    
    @Override
    public void ip_setCamera(Camera camera_) {
        mainCamera = camera_;
    }
    
}

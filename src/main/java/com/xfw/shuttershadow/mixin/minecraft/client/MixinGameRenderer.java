package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.core.CoreSettings;
import com.xfw.shuttershadow.access.IEGameRenderer;
import com.xfw.shuttershadow.core.render.MyRenderHelper;
import com.xfw.shuttershadow.core.render.RenderStates;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;
import com.xfw.shuttershadow.client.ImmersiveCameraClient;

/** 接入相机远景绘制，并管理多世界渲染状态和生命周期。 */
@Mixin(GameRenderer.class)
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

    /** renderLevel 开头调用 ImmersiveCameraClient.render。 */
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$renderImmersiveCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (ImmersiveCameraClient.render(deltaTracker)) ci.cancel();
    }

    /** 远景天空与地形雾采用相机视距，普通世界保留原渲染距离。 */
    @ModifyReturnValue(method = "getRenderDistance", at = @At("RETURN"))
    private float shuttershadow$cameraFogDistance(float original) {
        return WorldRenderInfo.isRendering() ? WorldRenderInfo.getRenderDistance() * 16.0F : original;
    }

    // 相机远景渲染期间保留主世界准星目标，避免在临时世界中重算。
    /** 远景渲染时保留源世界的准星交互目标。 */
    @Inject(method = "Lnet/minecraft/client/renderer/GameRenderer;pick(F)V", at = @At("HEAD"), cancellable = true)
    private void onUpdateTargetedEntity(float partialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            if (WorldRenderInfo.isRendering()) {
                ci.cancel();
            }
        }
    }

    /** 世界渲染前更新相机状态、发送渲染事件并上传远景网格。 */
    @Inject(method = "render", at = @At("HEAD"))
    private void onFarBeforeRendering(
        DeltaTracker deltaTracker, boolean renderWorldIn, CallbackInfo ci
    ) {
        if (minecraft.level == null) {
            return;
        }
        if (!renderWorldIn) { // 重生期间仍会执行客户端刻及渲染。
            return;
        }
        minecraft.getProfiler().push("ip_pre_render");
        // 使用当前刻的插值进度，不使用刻增量。
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        RenderStates.updatePreRenderInfo(partialTick);
        NeoForge.EVENT_BUS.post(new CoreSettings.PreGameRenderEvent());
        MyRenderHelper.earlyRemoteUpload();
        minecraft.getProfiler().pop();
        
    }
    
    // 玩家主世界渲染前执行，远景渲染不会触发。
    
    // 玩家主世界渲染后执行，远景渲染不会触发。
    /** 世界渲染后清理临时状态并刷新延迟光照。 */
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
    
    // 处理第三人称视角的相机变换。
    
    
    
    // 窗口大小改变时同步调整所有世界渲染器。
    /** 窗口调整大小时同步调整后台世界渲染器。 */
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
    
    /** 远景摇晃平移归零，普通视角原样调用并保留旋转和其他模组的包装。 */
    @WrapOperation(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V")
    )
    private void shuttershadow$cameraBobbingTranslation(PoseStack poses, float x, float y, float z,
                                                       Operation<Void> original) {
        if (!WorldRenderInfo.isRendering()) {
            original.call(poses, x, y, z);
            return;
        }
        original.call(poses, 0.0F, 0.0F, 0.0F);
    }


    /** 设置 GameRenderer.lightTexture，内部切世界时换用对应维度光照纹理并在退出恢复。 */
    @Override
    public void ip_setLightmapTextureManager(LightTexture manager) {
        lightTexture = manager;
    }
    
    /** 读取 renderHand，供临时世界渲染保存原手部绘制选项。 */
    @Override
    public boolean ip_getDoRenderHand() {
        return renderHand;
    }
    
    /** 设置 mainCamera，供远景与截图切换观察相机，调用者负责恢复。 */
    @Override
    public void ip_setCamera(Camera camera_) {
        mainCamera = camera_;
    }
    
}

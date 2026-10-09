package com.xfw.dimensionalexposure.mixin.compat.iris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.xfw.dimensionalexposure.client.ImmersiveCameraClient;
import io.github.mortuusars.exposure.client.capture.task.BackgroundScreenshotCaptureTask;
import net.irisshaders.iris.targets.Blaze3dRenderTargetExt;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 手动远景截图切换目标时，恢复 Iris 的颜色纹理附件。 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer", remap = false)
public abstract class IrisCameraColorTargetMixin {
    @Shadow private int lastColorTextureVersion;
    @Shadow private int lastColorTextureId;
    @Unique private boolean dimensionalExposure$cameraColorPending;
    @Unique private RenderTarget dimensionalExposure$cameraTarget;

    /** 注入 renderFinalPass 开头。 */
    @Inject(method = "renderFinalPass", at = @At("HEAD"))
    private void dimensionalExposure$checkCameraColor(CallbackInfo ci) {
        if (ImmersiveCameraClient.isStandScreenshot() && BackgroundScreenshotCaptureTask.isCapturing()) {
            dimensionalExposure$cameraColorPending = true;
        }
        if (!dimensionalExposure$cameraColorPending) return;
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        boolean targetChanged = dimensionalExposure$cameraTarget != target;
        dimensionalExposure$cameraTarget = target;
        if (!targetChanged) return;
        int version = ((Blaze3dRenderTargetExt) target).iris$getColorBufferVersion();
        int texture = target.getColorTextureId();
        if (lastColorTextureVersion != version || lastColorTextureId != texture) return;
        // 相同数字编号可能代表新的 GL 对象；交由 Iris 原生分支更新 colorHolder 附件。
        lastColorTextureVersion = version ^ 1;
    }

    /** 注入 renderFinalPass 返回。 */
    @Inject(method = "renderFinalPass", at = @At("RETURN"))
    private void dimensionalExposure$finishCameraColor(CallbackInfo ci) {
        if (!BackgroundScreenshotCaptureTask.isCapturing()) {
            dimensionalExposure$cameraColorPending = false;
            dimensionalExposure$cameraTarget = null;
        }
    }
}

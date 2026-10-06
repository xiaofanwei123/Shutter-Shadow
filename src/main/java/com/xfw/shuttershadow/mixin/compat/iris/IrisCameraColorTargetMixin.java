package com.xfw.shuttershadow.mixin.compat.iris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.xfw.shuttershadow.client.RemoteStandCapture;
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

/** 仅手动远景照片恢复颜色附件，红石源照片使用原生管线。 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer", remap = false)
public abstract class IrisCameraColorTargetMixin {
    @Shadow private int lastColorTextureVersion;
    @Shadow private int lastColorTextureId;
    @Unique private boolean shuttershadow$cameraColorPending;
    @Unique private RenderTarget shuttershadow$cameraTarget;

    @Inject(method = "renderFinalPass", at = @At("HEAD"))
    private void shuttershadow$checkCameraColor(CallbackInfo ci) {
        if (RemoteStandCapture.isRenderingScreenshot() && BackgroundScreenshotCaptureTask.isCapturing()) {
            shuttershadow$cameraColorPending = true;
        }
        if (!shuttershadow$cameraColorPending) return;
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        boolean targetChanged = shuttershadow$cameraTarget != target;
        shuttershadow$cameraTarget = target;
        if (!targetChanged) return;
        int version = ((Blaze3dRenderTargetExt) target).iris$getColorBufferVersion();
        int texture = target.getColorTextureId();
        if (lastColorTextureVersion != version || lastColorTextureId != texture) return;
        // 相同数字编号可能代表新的 GL 对象；交由 Iris 原生分支更新 colorHolder 附件。
        lastColorTextureVersion = version ^ 1;
    }

    @Inject(method = "renderFinalPass", at = @At("RETURN"))
    private void shuttershadow$finishCameraColor(CallbackInfo ci) {
        if (!BackgroundScreenshotCaptureTask.isCapturing()) {
            shuttershadow$cameraColorPending = false;
            shuttershadow$cameraTarget = null;
        }
    }
}

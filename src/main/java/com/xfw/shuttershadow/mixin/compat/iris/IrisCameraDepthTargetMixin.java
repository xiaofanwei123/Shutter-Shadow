package com.xfw.shuttershadow.mixin.compat.iris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.xfw.shuttershadow.client.RemoteStandCapture;
import io.github.mortuusars.exposure.client.capture.task.BackgroundScreenshotCaptureTask;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 仅手动远景照片恢复临时截图深度附件，红石照片不切换远景管线。 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.targets.RenderTargets", remap = false)
public abstract class IrisCameraDepthTargetMixin {
    @Shadow private int currentDepthTexture;
    @Shadow private int cachedDepthBufferVersion;
    @Unique private boolean shuttershadow$cameraDepthPending;
    @Unique private RenderTarget shuttershadow$cameraTarget;

    @Inject(method = "resizeIfNeeded", at = @At("HEAD"))
    private void shuttershadow$checkCameraDepth(int depthBufferVersion, int depthTexture,
                                               int width, int height, DepthBufferFormat depthFormat,
                                               PackDirectives directives, CallbackInfoReturnable<Boolean> cir) {
        boolean cameraCapture = RemoteStandCapture.isRenderingScreenshot()
                && BackgroundScreenshotCaptureTask.isCapturing();
        if (cameraCapture) shuttershadow$cameraDepthPending = true;
        if (!shuttershadow$cameraDepthPending) return;
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        boolean targetChanged = shuttershadow$cameraTarget != target;
        shuttershadow$cameraTarget = target;
        if ((!targetChanged && currentDepthTexture == depthTexture)
                || cachedDepthBufferVersion != depthBufferVersion) return;
        // 新目标可能复用已删除纹理的数字编号，故同时检查目标对象，而非只比较编号。
        // 只使原生版本判断失效，由 Iris 更新所有深度附件。
        cachedDepthBufferVersion = depthBufferVersion ^ 1;
    }

    @Inject(method = "resizeIfNeeded", at = @At("RETURN"))
    private void shuttershadow$finishCameraDepth(int depthBufferVersion, int depthTexture,
                                                int width, int height, DepthBufferFormat depthFormat,
                                                PackDirectives directives, CallbackInfoReturnable<Boolean> cir) {
        // 目标管线可能暂时不再绘制；留到它下一次正常取景完成重绑后才结束恢复检查。
        if (!BackgroundScreenshotCaptureTask.isCapturing()) {
            shuttershadow$cameraDepthPending = false;
            shuttershadow$cameraTarget = null;
        }
    }
}

package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import io.github.mortuusars.exposure.client.capture.task.BackgroundScreenshotCaptureTask;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** IP 托管远景渲染器，截图不反复重建玩家当前世界的透明渲染资源。 */
@Mixin(value = BackgroundScreenshotCaptureTask.class, remap = false)
public abstract class BackgroundScreenshotRemoteMixin {
    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;graphicsChanged()V", remap = true))
    private void shuttershadow$keepWorldSections(LevelRenderer renderer, Operation<Void> original) {
        if (!ImmersiveCameraClient.isStandScreenshot()) original.call(renderer);
    }
}

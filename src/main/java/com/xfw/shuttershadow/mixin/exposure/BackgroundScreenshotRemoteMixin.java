package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import io.github.mortuusars.exposure.client.capture.task.BackgroundScreenshotCaptureTask;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 让支架远景截图保留已经编译好的区块网格。 */
@Mixin(value = BackgroundScreenshotCaptureTask.class, remap = false)
public abstract class BackgroundScreenshotRemoteMixin {
    /** 支架远景截图时保留现有区块网格，避免截图前清空地形。 */
    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;graphicsChanged()V", remap = true))
    private void shuttershadow$keepWorldSections(LevelRenderer renderer, Operation<Void> original) {
        if (!ImmersiveCameraClient.isStandScreenshot()) original.call(renderer);
    }
}

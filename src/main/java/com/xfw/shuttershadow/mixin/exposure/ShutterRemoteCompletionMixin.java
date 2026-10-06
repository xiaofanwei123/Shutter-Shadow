package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.network.RemoteStandPreparation;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.item.camera.Shutter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 维度支架的咔嚓声表示图像已完成、胶卷帧已提交；手持快门沿用原声。 */
@Mixin(value = Shutter.class, remap = false)
public abstract class ShutterRemoteCompletionMixin {
    @Inject(method = "playOpenSound", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$deferOpenSound(CameraHolder holder, CallbackInfo ci) {
        if (RemoteStandPreparation.deferShutterSound(holder, false)) ci.cancel();
    }

    @Inject(method = "playCloseSound", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$deferCloseSound(CameraHolder holder, CallbackInfo ci) {
        if (RemoteStandPreparation.deferShutterSound(holder, true)) ci.cancel();
    }
}

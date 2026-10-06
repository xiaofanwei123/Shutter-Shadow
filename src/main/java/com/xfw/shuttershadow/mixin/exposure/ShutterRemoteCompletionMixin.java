package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.network.RemoteStandPreparation;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.item.camera.Shutter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 将维度支架的快门声音延迟到截图验收和胶卷提交之后。 */
@Mixin(value = Shutter.class, remap = false)
public abstract class ShutterRemoteCompletionMixin {
    /** 将支架快门打开声延迟到拍摄事务完成。 */
    @Inject(method = "playOpenSound", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$deferOpenSound(CameraHolder holder, CallbackInfo ci) {
        if (RemoteStandPreparation.deferShutterSound(holder, false)) ci.cancel();
    }

    /** 将支架快门关闭声延迟到出片完成，避免提前取下胶卷。 */
    @Inject(method = "playCloseSound", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$deferCloseSound(CameraHolder holder, CallbackInfo ci) {
        if (RemoteStandPreparation.deferShutterSound(holder, true)) ci.cancel();
    }
}

package com.xfw.shuttershadow.mixin.exposure;

import io.github.mortuusars.exposure.PlatformHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在固定事件入口停用三个原生拍摄事件，保留其他 Exposure 行为和事件。 */
@Mixin(value = PlatformHelper.class, remap = false)
public abstract class ExposureFrameEventsMixin {
    /** 统一取消原生帧元数据、实体元数据和帧完成通知，由本模组拍摄事件接管。 */
    @Inject(method = {"postModifyEntityInFrameExtraDataEvent", "postModifyFrameExtraDataEvent", "postFrameAddedEvent"},
            at = @At("HEAD"), cancellable = true)
    private static void shuttershadow$disableNativeFrameEvents(CallbackInfo callback) {
        callback.cancel();
    }
}

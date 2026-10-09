package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.api.event.CameraViewEvent;
import com.xfw.shuttershadow.client.CameraViewEvents;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder;
import io.github.mortuusars.exposure.world.camera.Camera;
import io.github.mortuusars.exposure.world.camera.CameraInHand;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 换槽时安全关闭旧手持取景器，避免自拍状态写入另一台相机。 */
@Mixin(value = Viewfinder.class, remap = false)
public abstract class ViewfinderInvalidationMixin {
    @Shadow @Final protected Camera camera;
    /** 换槽后关闭旧相机取景器，避免自拍设置写入新相机。 */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$closeDetachedHandheld(CallbackInfo callback) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && CameraClient.viewfinder() == (Object) this
                && camera instanceof CameraInHand
                && !camera.getId().matches(camera.getItemStack())) {
            // CameraInHand 按原相机 ID 查找手中或背包内的旧相机，不会修改新拿出的相机。
            camera.deactivate();
            try {
                CameraViewEvents.close(CameraViewEvent.CloseReason.CAMERA_CHANGED);
            } finally {
                // 原生关闭负责恢复视角和释放取景器后处理；不发送可能停用新相机的通用关闭包。
                ((CameraOperator) mc.player).removeActiveExposureCamera();
                callback.cancel();
            }
        }
    }

}

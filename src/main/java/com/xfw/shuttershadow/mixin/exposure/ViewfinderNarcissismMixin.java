package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.camera.CameraEnchantments;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder;
import io.github.mortuusars.exposure.client.camera.viewfinder.ViewfinderSelfie;
import io.github.mortuusars.exposure.world.camera.Camera;
import io.github.mortuusars.exposure.world.camera.CameraInHand;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 实现默认自拍并在换槽时安全关闭旧手持取景器。 */
@Mixin(value = Viewfinder.class, remap = false)
public abstract class ViewfinderNarcissismMixin {
    @Shadow @Final protected Camera camera;
    /** Shadow 暴露原 selfie 子模块，实际更新仍由 Exposure 执行。 */
    @Shadow public abstract ViewfinderSelfie selfie();

    /** 换槽后关闭旧相机取景器，避免自拍设置写入新相机。 */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$closeDetachedHandheld(CallbackInfo callback) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && CameraClient.viewfinder() == (Object) this
                && camera instanceof CameraInHand
                && !camera.getId().matches(camera.getItemStack())) {
            // CameraInHand 按原相机 ID 查找手中或背包内的旧相机，不会修改新拿出的相机。
            camera.deactivate();
            // 原生关闭负责恢复视角和释放取景器后处理；不发送可能停用新相机的通用关闭包。
            ((CameraOperator) mc.player).removeActiveExposureCamera();
            callback.cancel();
        }
    }

    /** 自恋狂相机默认使用正面自拍，并同步取景器状态。 */
    @Inject(method = "setup", at = @At(value = "RETURN", ordinal = 1))
    private void shuttershadow$defaultSelfie(CallbackInfo callback) {
        if (CameraClient.viewfinder() == (Object) this
                && camera instanceof CameraInHand
                && CameraEnchantments.has(camera.getItemStack(), CameraEnchantments.NARCISSISM)) {
            Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            selfie().updateSelfieMode();
        }
    }
}

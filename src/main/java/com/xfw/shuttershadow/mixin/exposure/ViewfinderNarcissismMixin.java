package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.CameraEnchantments;
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

/** 保持手持取景器与相机身份一致，默认自拍和关闭复位仍沿用 Exposure。 */
@Mixin(value = Viewfinder.class, remap = false)
public abstract class ViewfinderNarcissismMixin {
    @Shadow @Final protected Camera camera;
    @Shadow public abstract ViewfinderSelfie selfie();

    /** 换槽后先关闭旧取景器，避免其自拍更新把设置写入新拿出的相机。 */
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

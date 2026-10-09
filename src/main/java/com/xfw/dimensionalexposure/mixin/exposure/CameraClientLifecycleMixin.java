package com.xfw.dimensionalexposure.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.xfw.dimensionalexposure.client.CameraViewLifecycle;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.world.camera.Camera;
import org.spongepowered.asm.mixin.Mixin;

/** 在 Exposure 真实取景器开关处同步默认自拍和同相机重绑定模式。 */
@Mixin(value = CameraClient.class, remap = false)
public abstract class CameraClientLifecycleMixin {
    /** 原生初始化先保存旧视角，再应用或恢复自拍模式。 */
    @WrapMethod(method = "setupViewfinder")
    private static void dimensionalExposure$open(Camera camera, Operation<Void> original) {
        CameraViewLifecycle.beginSetup(camera);
        boolean initialized = false;
        try {
            original.call(camera);
            initialized = true;
        } finally {
            CameraViewLifecycle.finishSetup(initialized ? CameraClient.viewfinder() : null);
        }
    }

    /** 原生清理后结束逻辑会话，后台相机实体切换不影响取景器生命周期。 */
    @WrapMethod(method = "removeViewfinder")
    private static void dimensionalExposure$close(Operation<Void> original) {
        try {
            original.call();
        } finally {
            CameraViewLifecycle.removed();
        }
    }
}

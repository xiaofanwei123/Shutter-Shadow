package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.xfw.shuttershadow.client.CameraViewEvents;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.world.camera.Camera;
import org.spongepowered.asm.mixin.Mixin;

/** 在 Exposure 的真实取景器开关处发布相机观察事件。 */
@Mixin(value = CameraClient.class, remap = false)
public abstract class CameraClientLifecycleMixin {
    /** 原生初始化先保存旧视角，再应用打开事件决定的模式。 */
    @WrapMethod(method = "setupViewfinder")
    private static void shuttershadow$open(Camera camera, Operation<Void> original) {
        CameraViewEvents.beginSetup(camera);
        boolean initialized = false;
        try {
            original.call(camera);
            initialized = true;
        } finally {
            CameraViewEvents.finishSetup(initialized ? CameraClient.viewfinder() : null);
        }
    }

    /** 原生清理后结束逻辑会话，后台相机实体切换不触发此事件。 */
    @WrapMethod(method = "removeViewfinder")
    private static void shuttershadow$close(Operation<Void> original) {
        try {
            original.call();
        } finally {
            CameraViewEvents.removed();
        }
    }
}

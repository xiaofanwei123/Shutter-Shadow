package com.xfw.dimensionalexposure.compat;


import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import com.xfw.dimensionalexposure.util.Helper;

import java.lang.reflect.Field;

/** Iris兼容入口持有默认Invoker。 */
public class IrisInterface {

    /** 没有Iris时的安全空适配器，不引用Iris实现对象。 */
    public static class Invoker {
        /** 返回false，无阴影pass。 */
        public boolean isRenderingShadowMap() {
            return false;
        }

        /** 返回null，无Iris pipeline。 */
        public Object getPipeline(LevelRenderer worldRenderer) {
            return null;
        }

        /** 空实现，不修改renderer。 */
        public void setPipeline(LevelRenderer worldRenderer, Object pipeline) {

        }

    }

    /** Iris存在时的适配器。 */
    public static class OnIrisPresent extends Invoker {

        private final Field worldRendererPipelineField = Helper.noError(() -> {
            Field field = LevelRenderer.class.getDeclaredField("pipeline");
            field.setAccessible(true);
            return field;
        });

        /** 读取ShadowRenderer.ACTIVE辨认阴影pass。 */
        @Override
        public boolean isRenderingShadowMap() {
            return ShadowRenderer.ACTIVE;
        }

        /** 读取指定世界渲染器的光影管线，并安全处理反射异常。 */
        @Override
        public Object getPipeline(LevelRenderer worldRenderer) {
            return Helper.noError(() ->
                ((WorldRenderingPipeline) worldRendererPipelineField.get(worldRenderer))
            );
        }

        // 开启光影时无需额外切换渲染管线。
        // 关闭光影时仍需切换渲染管线。
        /** 经缓存Field写指定renderer的pipeline，支持目标绘制期间临时置null再恢复。 */
        @Override
        public void setPipeline(LevelRenderer worldRenderer, Object pipeline) {
            Helper.noError(() -> {
                worldRendererPipelineField.set(worldRenderer, pipeline);
                return null;
            });
        }

    }

    public static Invoker invoker = new Invoker();
}

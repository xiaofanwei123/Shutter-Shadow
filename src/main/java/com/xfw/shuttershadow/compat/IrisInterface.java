package com.xfw.shuttershadow.compat;
// Shuttershadow phase seven: relocated into the camera core.

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import com.xfw.shuttershadow.util.Helper;

import java.lang.reflect.Field;

public class IrisInterface {

    public static class Invoker {
        public boolean isRenderingShadowMap() {
            return false;
        }

        public Object getPipeline(LevelRenderer worldRenderer) {
            return null;
        }

        public void setPipeline(LevelRenderer worldRenderer, Object pipeline) {

        }

    }

    public static class OnIrisPresent extends Invoker {

        private final Field worldRendererPipelineField = Helper.noError(() -> {
            Field field = LevelRenderer.class.getDeclaredField("pipeline");
            field.setAccessible(true);
            return field;
        });

        @Override
        public boolean isRenderingShadowMap() {
            return ShadowRenderer.ACTIVE;
        }

        @Override
        public Object getPipeline(LevelRenderer worldRenderer) {
            return Helper.noError(() ->
                ((WorldRenderingPipeline) worldRendererPipelineField.get(worldRenderer))
            );
        }

        // the pipeline switching is unnecessary when using shaders
        // but still necessary with shaders disabled
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

package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.xfw.shuttershadow.core.render.FogRendererContext;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

/** 保存各维度雾颜色状态，并让远景雾颜色匹配相机视距。 */
@Mixin(value = FogRenderer.class, priority = 1100)
public class MixinFogRenderer {
    /** 远景雾颜色也按相机视距混合，与天空和地形雾保持一致。 */
    @ModifyVariable(method = "setupColor", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int shuttershadow$cameraFogColorDistance(int original) {
        return WorldRenderInfo.isRendering() ? WorldRenderInfo.getRenderDistance() : original;
    }

    @Shadow
    private static float fogRed;
    @Shadow
    private static float fogGreen;
    @Shadow
    private static float fogBlue;
    @Shadow
    private static int targetBiomeFog = -1;
    @Shadow
    private static int previousBiomeFog = -1;
    @Shadow
    private static long biomeChangedTime = -1L;
    
    static {
        FogRendererContext.copyContextFromObject = context -> {
            fogRed = context.red;
            fogGreen = context.green;
            fogBlue = context.blue;
            targetBiomeFog = context.targetBiomeFog;
            previousBiomeFog = context.previousBiomeFog;
            biomeChangedTime = context.biomeChangedTime;
        };
        
        FogRendererContext.copyContextToObject = context -> {
            context.red = fogRed;
            context.green = fogGreen;
            context.blue = fogBlue;
            context.targetBiomeFog = targetBiomeFog;
            context.previousBiomeFog = previousBiomeFog;
            context.biomeChangedTime = biomeChangedTime;
        };
        
        FogRendererContext.getCurrentFogColor =
            () -> new Vec3(fogRed, fogGreen, fogBlue);
        
        FogRendererContext.init();
    }
}

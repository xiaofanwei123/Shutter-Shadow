package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.xfw.shuttershadow.core.render.FogRendererContext;

/** 保存和恢复各维度的雾颜色及渐变状态。 */
@Mixin(value = FogRenderer.class, priority = 1100)
public class MixinFogRenderer {
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

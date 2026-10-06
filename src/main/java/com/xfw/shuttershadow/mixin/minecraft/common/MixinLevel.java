package com.xfw.shuttershadow.mixin.minecraft.common;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.access.IEWorld;

@Mixin(Level.class)
public abstract class MixinLevel implements IEWorld {
    
    @Shadow
    public abstract ResourceKey<Level> dimension();
    
    @Shadow
    protected float rainLevel;
    
    @Shadow
    protected float thunderLevel;
    
    @Shadow
    protected float oRainLevel;
    
    @Shadow
    protected float oThunderLevel;
    
    @Shadow
    @Final
    private Thread thread;
    
    // Fix overworld rain cause nether fog change
    @Inject(method = "Lnet/minecraft/world/level/Level;prepareWeather()V", at = @At("TAIL"))
    private void onInitWeatherGradients(CallbackInfo ci) {
        if (dimension() == Level.NETHER) {
            rainLevel = 0;
            oRainLevel = 0;
            thunderLevel = 0;
            oThunderLevel = 0;
        }
    }
    
    @Override
    public Thread portal_getThread() {
        return thread;
    }
}

package com.xfw.dimensionalexposure.mixin.minecraft.common;


import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.dimensionalexposure.access.IEWorld;

/** 提供世界线程访问，并修正下界初始化时的雨雷渐变。 */
@Mixin(Level.class)
public abstract class MixinLevel implements IEWorld {
    
    /** Shadow 引用原 dimension，供天气维度判定。 */
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
    
    // 避免主世界降雨改变下界远景的雾效。
    /** 清零下界雨雷渐变，避免源世界天气影响下界画面。 */
    @Inject(method = "Lnet/minecraft/world/level/Level;prepareWeather()V", at = @At("TAIL"))
    private void onInitWeatherGradients(CallbackInfo ci) {
        if (dimension() == Level.NETHER) {
            rainLevel = 0;
            oRainLevel = 0;
            thunderLevel = 0;
            oThunderLevel = 0;
        }
    }
    
    /** 返回 Level.thread，供世界包处理/区块线程检查，不启动任务。 */
    @Override
    public Thread portal_getThread() {
        return thread;
    }
}

package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.BiomeAmbientSoundsHandler;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 让环境音的生物群系来源随玩家真实世界变化。 */
@Mixin(BiomeAmbientSoundsHandler.class)
public class MixinBiomeAmbientSoundPlayer {
    @Mutable
    @Shadow
    @Final
    private BiomeManager biomeManager;
    
    @Shadow
    @Final
    private LocalPlayer player;
    
    // 玩家换维后更新环境音使用的生物群系来源。
    /** 随玩家所在世界刷新环境音的生物群系来源。 */
    @Inject(method = "Lnet/minecraft/client/resources/sounds/BiomeAmbientSoundsHandler;tick()V", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        biomeManager = player.level().getBiomeManager();
    }
}

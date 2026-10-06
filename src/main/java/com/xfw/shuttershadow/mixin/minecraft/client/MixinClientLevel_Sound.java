package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shuttershadow 第五轮：仅保留玩家所在世界的位置音效，移除实体门户声音转发。 */
@Mixin(ClientLevel.class)
public class MixinClientLevel_Sound {
    @Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
    private void onPlaySound(double x, double y, double z, SoundEvent soundEvent,
                             SoundSource soundSource, float volume, float pitch,
                             boolean distanceDelay, long seed, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player == null || (Object) this != player.level()) ci.cancel();
    }
}

package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 仅播放玩家真实世界的声音，隔离后台维度音效。 */
@Mixin(ClientLevel.class)
public class MixinClientLevel_Sound {
    /** playSound 开头取消没有本地玩家或该 ClientLevel 不是玩家实际 level 的调用。 */
    @Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
    private void onPlaySound(double x, double y, double z, SoundEvent soundEvent,
                             SoundSource soundSource, float volume, float pitch,
                             boolean distanceDelay, long seed, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player == null || (Object) this != player.level()) ci.cancel();
    }
}

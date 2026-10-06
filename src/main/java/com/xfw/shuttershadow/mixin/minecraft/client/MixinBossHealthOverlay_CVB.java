package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BossHealthOverlay.class)
// Shuttershadow 第三轮整理：从外围模块迁入渲染内核，保持相机远景的 Boss 雾行为不变。
public class MixinBossHealthOverlay_CVB {
    // the boss info does not get synced through portal
    // so when the player jumps into end portal the fog will abruptly become thick
    // avoid thicken fog to make the teleportation seamless
    @Inject(method = "Lnet/minecraft/client/gui/components/BossHealthOverlay;shouldCreateWorldFog()Z", at = @At("HEAD"), cancellable = true)
    private void onShouldThickenFog(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
        cir.cancel();
    }
}

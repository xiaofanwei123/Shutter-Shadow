package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 全局禁用首领雾效，避免多世界画面和无缝换维时突变。 */
@Mixin(BossHealthOverlay.class)
public class MixinBossHealthOverlay_CVB {
    // 远景世界没有同步完整的首领状态。
    // 换维时原版首领雾可能突然变浓。
    // 抑制这类雾效突变，保持无缝传送画面连续。
    /** 禁用首领雾效，避免多世界画面和换维时出现雾浓度突变。 */
    @Inject(method = "Lnet/minecraft/client/gui/components/BossHealthOverlay;shouldCreateWorldFog()Z", at = @At("HEAD"), cancellable = true)
    private void onShouldThickenFog(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
        cir.cancel();
    }
}

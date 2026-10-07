package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.gui.components.BossHealthOverlay;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 仅在相机远景中隔离来源世界的首领雾，正常画面沿用原版。 */
@Mixin(BossHealthOverlay.class)
public class MixinBossHealthOverlay_CVB {
    /** 远景不继承玩家世界的首领雾，返回玩家世界后立即恢复原判定。 */
    @Inject(method = "Lnet/minecraft/client/gui/components/BossHealthOverlay;shouldCreateWorldFog()Z", at = @At("HEAD"), cancellable = true)
    private void onShouldThickenFog(CallbackInfoReturnable<Boolean> cir) {
        if (WorldRenderInfo.isRendering()) cir.setReturnValue(false);
    }
}

package com.xfw.shuttershadow.mixin.exposure;

import io.github.mortuusars.exposure.client.camera.viewfinder.ViewfinderOverlay;
import io.github.mortuusars.exposure.world.camera.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在取景器右下角显示已本地化的自拍模式标签。 */
@Mixin(value = ViewfinderOverlay.class, remap = false)
public abstract class ViewfinderOverlaySelfieMixin {
    @Shadow
    protected Camera camera;

    /** 注入 render 尾部：有相机且 inSelfieMode 时在右下角绘制本模组自拍翻译文字。 */
    @Inject(method = "render", at = @At("TAIL"))
    private void shuttershadow$renderSelfieLabel(GuiGraphics graphics,
                                                 DeltaTracker deltaTracker,
                                                 CallbackInfo callback) {
        if (camera == null || !camera.inSelfieMode()) return;

        Component label = Component.translatable("shuttershadow.camera.selfie_mode");
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int x = graphics.guiWidth() - font.width(label) - 12;
        int y = graphics.guiHeight() - font.lineHeight - 12;
        graphics.drawString(font, label, x, y, 0xFFFFFFFF, true);
    }
}

package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

// Shuttershadow 第六轮：只保留远景相机的区块视角坐标修正。
@Mixin(value = LevelRenderer.class, priority = 1100)
public class MixinLevelRenderer_Optional {
    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"), require = 0)
    private double redirectGetXInSetupRender(LocalPlayer player) {
        return WorldRenderInfo.isRendering() ? WorldRenderInfo.getCameraPos().x : player.getX();
    }

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"), require = 0)
    private double redirectGetYInSetupRender(LocalPlayer player) {
        return WorldRenderInfo.isRendering() ? WorldRenderInfo.getCameraPos().y : player.getY();
    }

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"), require = 0)
    private double redirectGetZInSetupRender(LocalPlayer player) {
        return WorldRenderInfo.isRendering() ? WorldRenderInfo.getCameraPos().z : player.getZ();
    }
}

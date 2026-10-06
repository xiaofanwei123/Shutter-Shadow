package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

// Shuttershadow 第六轮：只保留远景相机的区块视角坐标修正。
/** 在额外世界渲染时修正原版地形查询使用的玩家坐标。 */
@Mixin(value = LevelRenderer.class, priority = 1100)
public class MixinLevelRenderer_Optional {
    /** setupRender 中 LocalPlayer.getX 改为当前相机 X。 */
    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"), require = 0)
    private double redirectGetXInSetupRender(LocalPlayer player) {
        return WorldRenderInfo.isRendering() ? WorldRenderInfo.getCameraPos().x : player.getX();
    }

    /** setupRender 中 LocalPlayer.getY 改为当前相机 Y。 */
    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"), require = 0)
    private double redirectGetYInSetupRender(LocalPlayer player) {
        return WorldRenderInfo.isRendering() ? WorldRenderInfo.getCameraPos().y : player.getY();
    }

    /** setupRender 中 LocalPlayer.getZ 改为当前相机 Z。 */
    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"), require = 0)
    private double redirectGetZInSetupRender(LocalPlayer player) {
        return WorldRenderInfo.isRendering() ? WorldRenderInfo.getCameraPos().z : player.getZ();
    }
}

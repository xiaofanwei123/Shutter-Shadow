package com.xfw.shuttershadow.mixin.minecraft.client;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.xfw.shuttershadow.client.RemotePlayerRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the local operator to the remote scene only for this client's render pass. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererRemotePlayerMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endLastBatch()V",
            ordinal = 0, shift = At.Shift.BEFORE))
    private void shuttershadow$renderProjectedPlayer(
            DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
            GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projectionMatrix, Matrix4f modelViewMatrix, CallbackInfo callbackInfo,
            @Local PoseStack poseStack,
            @Local MultiBufferSource.BufferSource bufferSource) {
        RemotePlayerRenderer.render(
                (LevelRenderer) (Object) this, camera, deltaTracker, poseStack, bufferSource);
    }
}

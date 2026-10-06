package com.xfw.shuttershadow.mixin.minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xfw.shuttershadow.client.SourceStandCapture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 仅在红石源维度截图中隐藏玩家模型。 */
@Mixin(EntityRenderDispatcher.class)
public abstract class SourceStandEntityRenderMixin {
    /** 红石源世界截图时隐藏玩家模型。 */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$hidePhotographedPlayers(Entity entity, double x, double y, double z,
                                                       float yaw, float partialTick, PoseStack poses,
                                                       MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (entity instanceof Player && SourceStandCapture.isRenderingSourceScene()) ci.cancel();
    }
}

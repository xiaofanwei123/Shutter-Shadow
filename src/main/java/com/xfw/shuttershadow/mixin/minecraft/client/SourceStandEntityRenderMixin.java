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

/** 红石原生支架截图省略玩家模型、名字和阴影，普通世界和手动相机不受影响。 */
@Mixin(EntityRenderDispatcher.class)
public abstract class SourceStandEntityRenderMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$hidePhotographedPlayers(Entity entity, double x, double y, double z,
                                                       float yaw, float partialTick, PoseStack poses,
                                                       MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (entity instanceof Player && SourceStandCapture.isRenderingSourceScene()) ci.cancel();
    }
}

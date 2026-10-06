package com.xfw.shuttershadow.mixin.compat.sodium;
// Shuttershadow phase seven: relocated into the camera core.

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

// Shuttershadow 第六轮：远景相机禁用洞穴遮挡，玩家正常视角使用 Sodium 原值。
@Mixin(value = OcclusionCuller.class, remap = false)
public class MixinSodiumOcclusionCuller {
    @ModifyVariable(method = "findVisible", at = @At("HEAD"), argsOnly = true)
    private boolean modifyUseOcclusionCulling(boolean originalValue) {
        return !WorldRenderInfo.isRendering() && originalValue;
    }
}

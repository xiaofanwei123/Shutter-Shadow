package com.xfw.dimensionalexposure.mixin.compat.sodium;


import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.xfw.dimensionalexposure.core.render.WorldRenderInfo;

/** 仅在相机远景渲染中关闭洞穴遮挡剔除。 */
@Mixin(value = OcclusionCuller.class, remap = false)
public class MixinSodiumOcclusionCuller {
    /** 修改 findVisible 开头的 useOcclusionCulling 参数。 */
    @ModifyVariable(method = "findVisible", at = @At("HEAD"), argsOnly = true)
    private boolean modifyUseOcclusionCulling(boolean originalValue) {
        return !WorldRenderInfo.isRendering() && originalValue;
    }
}

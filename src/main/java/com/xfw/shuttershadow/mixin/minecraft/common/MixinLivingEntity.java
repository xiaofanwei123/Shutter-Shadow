package com.xfw.shuttershadow.mixin.minecraft.common;


import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 清理生物换维后指向其它世界的战斗目标引用。 */
@Mixin(LivingEntity.class)
public class MixinLivingEntity {
    
    /** tick 返回：lastHurtByMob 属于别的世界时清空该字段。 */
    @Inject(method = "Lnet/minecraft/world/entity/LivingEntity;tick()V", at = @At("RETURN"))
    private void onTickEnded(CallbackInfo ci) {
        LivingEntity this_ = (LivingEntity) (Object) this;
        if (this_.getLastHurtByMob() != null) {
            if (this_.getLastHurtByMob().level() != this_.level()) {
            	this_.setLastHurtByMob(null);
            }
        }
        if (this_.getLastHurtMob() != null) {
            if (this_.getLastHurtMob().level() != this_.level()) {
            	this_.setLastHurtByPlayer(null);
            }
        }
    }
}

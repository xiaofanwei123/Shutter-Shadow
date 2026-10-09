package com.xfw.dimensionalexposure.camera;

import com.xfw.dimensionalexposure.DimensionalExposure;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** 相机跨维传送后给予缓降，并在有限时间内免疫无实体来源的伤害。 */
@EventBusSubscriber(modid = DimensionalExposure.MODID)
public final class CameraTeleportSafetyEffect extends MobEffect {
    /** 建立正面的安全传送效果。 */
    public CameraTeleportSafetyEffect() {
        super(MobEffectCategory.BENEFICIAL, 0x78A7FF);
    }

    /** 按相机附魔等级给予十、二十或三十秒的缓降与保护。 */
    public static void apply(ServerPlayer player, int level) {
        if (level <= 0) return;
        int duration = Math.min(level, 3) * 200;
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, duration, 0));
        player.addEffect(new MobEffectInstance(DimensionalExposure.SAFE_DIMENSION_TELEPORT_EFFECT, duration, 0));
    }

    /** 仅保护有效果的服务端玩家；实体攻击与强制死亡仍沿用原版。 */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !player.hasEffect(DimensionalExposure.SAFE_DIMENSION_TELEPORT_EFFECT)) return;
        var source = event.getSource();
        if (source.getEntity() == null && source.getDirectEntity() == null
                && !source.is(DamageTypes.GENERIC_KILL)) event.setCanceled(true);
    }
}

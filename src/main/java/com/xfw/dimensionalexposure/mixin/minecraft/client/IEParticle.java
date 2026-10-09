package com.xfw.dimensionalexposure.mixin.minecraft.client;


import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 客户端 Particle 世界访问器，粒子筛选/tick 需要知道其创建时所属 ClientLevel。 */
@Mixin(Particle.class)
public interface IEParticle {
    /** Accessor 读取粒子的 level，返回所属世界，不改粒子位置。 */
    @Accessor("level")
    ClientLevel portal_getWorld();
}

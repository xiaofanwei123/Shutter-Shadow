package com.xfw.shuttershadow.mixin.minecraft.common;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.xfw.shuttershadow.access.IEEntity;

/** 保留直接换维使用的实体访问能力，不修改原版物理碰撞。 */
@Mixin(Entity.class)
public abstract class MixinEntity implements IEEntity {

    @Shadow
    private Level level;

    @Shadow
    protected abstract void unsetRemoved();

    @Override
    public void ip_unsetRemoved() {
        unsetRemoved();
    }
    
    @Override
    public void ip_setWorld(Level world) {
        this.level = world;
    }
}

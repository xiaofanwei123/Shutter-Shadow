package com.xfw.shuttershadow.mixin.minecraft.common;


import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.xfw.shuttershadow.access.IEEntity;

/** 提供无缝传送所需的实体世界和移除状态访问。 */
@Mixin(Entity.class)
public abstract class MixinEntity implements IEEntity {

    @Shadow
    private Level level;

    /** Shadow 引用原 unsetRemoved，不自行调用生命周期。 */
    @Shadow
    protected abstract void unsetRemoved();

    /** 调用原 unsetRemoved 清除移除标记，供无缝世界交接继续使用对象。 */
    @Override
    public void ip_unsetRemoved() {
        unsetRemoved();
    }
    
    /** 替换 Entity.level 引用，调用者还须更新追踪/包/玩家缓存等配套状态。 */
    @Override
    public void ip_setWorld(Level world) {
        this.level = world;
    }
}

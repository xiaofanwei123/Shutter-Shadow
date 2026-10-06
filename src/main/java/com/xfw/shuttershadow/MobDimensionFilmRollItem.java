package com.xfw.shuttershadow;

import io.github.mortuusars.exposure.world.camera.ExposureType;
import io.github.mortuusars.exposure.world.item.FilmRollItem;
import net.minecraft.world.item.Item;

/** 复用 Exposure 的彩色胶卷质量，迁移第一只出镜的非玩家 LivingEntity。 */
public final class MobDimensionFilmRollItem extends FilmRollItem {
    public MobDimensionFilmRollItem(Item.Properties properties) {
        super(ExposureType.COLOR, BAR_COLOR, properties);
    }
}

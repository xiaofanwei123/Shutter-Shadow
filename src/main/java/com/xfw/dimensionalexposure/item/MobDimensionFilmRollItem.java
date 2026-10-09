package com.xfw.dimensionalexposure.item;

import io.github.mortuusars.exposure.world.camera.ExposureType;
import io.github.mortuusars.exposure.world.item.FilmRollItem;
import net.minecraft.world.item.Item;

/** 生物维度胶卷的物品类型标记，继承Exposure彩色胶卷。 */
public final class MobDimensionFilmRollItem extends FilmRollItem {
    /** 使用COLOR曝光类型和本类胶卷条颜色构造父类。 */
    public MobDimensionFilmRollItem(Item.Properties properties) {
        super(ExposureType.COLOR, BAR_COLOR, properties);
    }
}

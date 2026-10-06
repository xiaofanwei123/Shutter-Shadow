package com.xfw.shuttershadow;

import io.github.mortuusars.exposure.world.camera.ExposureType;
import io.github.mortuusars.exposure.world.item.FilmRollItem;
import net.minecraft.world.item.Item;

/** 复用 Exposure 的彩色胶卷质量，附加玩家维度传送行为。 */
public final class PlayerDimensionFilmRollItem extends FilmRollItem {
    public PlayerDimensionFilmRollItem(Item.Properties properties) {
        super(ExposureType.COLOR, BAR_COLOR, properties);
    }
}

package com.xfw.shuttershadow.item;

import com.xfw.shuttershadow.Shuttershadow;

import com.xfw.shuttershadow.api.DimensionFilters;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 一个物品ID承载所有目标维度变体。 */
public final class DimensionFilterItem extends Item {
    /** 以传入Properties创建Exposure FilterItem。 */
    public DimensionFilterItem(Properties properties) {
        super(properties);
    }

    /** 有目标组件时生成item.shuttershadow.dimension_filter.<命名空间>.<路径>语言键。 */
    @Override
    public String getDescriptionId(ItemStack stack) {
        ResourceLocation target = DimensionFilters.target(stack);
        if (target == null) return super.getDescriptionId(stack);
        return "item." + Shuttershadow.MODID + ".dimension_filter."
                + target.getNamespace() + "." + target.getPath().replace('/', '.');
    }

    /** 按变体键取译名，缺少译名回退为带维度ID的英文名称。 */
    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation target = DimensionFilters.target(stack);
        if (target == null) return super.getName(stack);
        return Component.translatableWithFallback(getDescriptionId(stack),
                "Dimension Filter (" + target + ")");
    }
}

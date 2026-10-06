package com.xfw.shuttershadow;

import com.xfw.shuttershadow.api.DimensionFilters;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Exposure filter whose target dimension is stored as an item component.
 * A single registered item can therefore represent every data-pack route.
 */
public final class DimensionFilterItem extends Item {
    public DimensionFilterItem(Properties properties) {
        super(properties);
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        ResourceLocation target = DimensionFilters.target(stack);
        if (target == null) return super.getDescriptionId(stack);
        return "item." + Shuttershadow.MODID + ".dimension_filter."
                + target.getNamespace() + "." + target.getPath().replace('/', '.');
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation target = DimensionFilters.target(stack);
        if (target == null) return super.getName(stack);
        return Component.translatableWithFallback(getDescriptionId(stack),
                "Dimension Filter (" + target + ")");
    }
}

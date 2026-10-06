package com.xfw.shuttershadow;

import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** 定义曝光失效与自恋狂的附魔资源键。 */
public final class CameraEnchantments {
    public static final ResourceKey<Enchantment> EXPOSURE_FAILURE = key("exposure_failure");
    public static final ResourceKey<Enchantment> NARCISSISM = key("narcissism");

    /** 禁止建立实例。 */
    private CameraEnchantments() {
    }

    /** 把模组内路径组成ENCHANTMENT注册表资源键。 */
    private static ResourceKey<Enchantment> key(String path) {
        return ResourceKey.create(Registries.ENCHANTMENT,
                ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, path));
    }

    /** 先确认是CameraItem，再检查附魔组件中对应键的等级是否大于0。 */
    public static boolean has(ItemStack camera, ResourceKey<Enchantment> enchantment) {
        if (!(camera.getItem() instanceof CameraItem)) return false;
        for (var entry : camera.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
            if (entry.getIntValue() > 0 && entry.getKey().is(enchantment)) return true;
        }
        return false;
    }
}

package com.xfw.shuttershadow;

import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** 相机诅咒的稳定标识；附魔内容由数据包登记。 */
public final class CameraEnchantments {
    public static final ResourceKey<Enchantment> EXPOSURE_FAILURE = key("exposure_failure");
    public static final ResourceKey<Enchantment> NARCISSISM = key("narcissism");

    private CameraEnchantments() {
    }

    private static ResourceKey<Enchantment> key(String path) {
        return ResourceKey.create(Registries.ENCHANTMENT,
                ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, path));
    }

    /** 只读取相机上的实际附魔，不把附魔书的储存附魔当作相机效果。 */
    public static boolean has(ItemStack camera, ResourceKey<Enchantment> enchantment) {
        if (!(camera.getItem() instanceof CameraItem)) return false;
        for (var entry : camera.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
            if (entry.getIntValue() > 0 && entry.getKey().is(enchantment)) return true;
        }
        return false;
    }
}

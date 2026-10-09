package com.xfw.dimensionalexposure.camera;

import com.xfw.dimensionalexposure.DimensionalExposure;

import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** 定义相机附魔资源键，并读取相机上的附魔等级。 */
public final class CameraEnchantments {
    public static final ResourceKey<Enchantment> EXPOSURE_FAILURE = key("exposure_failure");
    public static final ResourceKey<Enchantment> NARCISSISM = key("narcissism");
    public static final ResourceKey<Enchantment> SAFE_DIMENSION_TELEPORT = key("safe_dimension_teleport");

    /** 禁止建立实例。 */
    private CameraEnchantments() {
    }

    /** 把模组内路径组成ENCHANTMENT注册表资源键。 */
    private static ResourceKey<Enchantment> key(String path) {
        return ResourceKey.create(Registries.ENCHANTMENT,
                ResourceLocation.fromNamespaceAndPath(DimensionalExposure.MODID, path));
    }

    /** 检查相机上是否具有指定附魔。 */
    public static boolean has(ItemStack camera, ResourceKey<Enchantment> enchantment) {
        return level(camera, enchantment) > 0;
    }

    /** 读取指定附魔等级，非相机或未附魔时返回零。 */
    public static int level(ItemStack camera, ResourceKey<Enchantment> enchantment) {
        if (!(camera.getItem() instanceof CameraItem)) return 0;
        for (var entry : camera.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
            if (entry.getKey().is(enchantment)) return entry.getIntValue();
        }
        return 0;
    }
}

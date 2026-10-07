package com.xfw.shuttershadow.api;

import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** 公开维度滤镜组件解析和原生坐标换算API。 */
public final class DimensionFilters {
    /** 禁止实例化此工具类。 */
    private DimensionFilters() {}

    /** 创建本模组滤镜并写非null目标维度组件。 */
    public static ItemStack create(ResourceLocation targetDimension) {
        ItemStack stack = Shuttershadow.DIMENSION_FILTER.get().getDefaultInstance();
        stack.set(Shuttershadow.DIMENSION_FILTER_TARGET.get(), Objects.requireNonNull(targetDimension));
        return stack;
    }

    /** 仅本模组滤镜返回目标组件，其他物品或null返回null。 */
    public static @Nullable ResourceLocation target(ItemStack filter) {
        return filter != null && filter.is(Shuttershadow.DIMENSION_FILTER.get())
                ? filter.get(Shuttershadow.DIMENSION_FILTER_TARGET.get()) : null;
    }

    /** 从滤镜目标组件解析跨维度路由，不限制来源维度。 */
    public static @Nullable Route resolve(ItemStack filter, ResourceLocation sourceDimension) {
        if (filter == null || filter.isEmpty() || sourceDimension == null) return null;
        ResourceLocation targetDimension = filter.get(Shuttershadow.DIMENSION_FILTER_TARGET.get());
        if (targetDimension == null || targetDimension.equals(sourceDimension)) return null;
        return new Route(BuiltInRegistries.ITEM.getKey(filter.getItem()), targetDimension);
    }

    /** 按来源和目标维度类型自动取得原生水平坐标倍率。 */
    public static double horizontalScale(Level source, Level target) {
        return DimensionType.getTeleportationScale(source.dimensionType(), target.dimensionType());
    }

    /** 按scale缩放X/Z，Y保持原值。 */
    public static Vec3 mapAbsolute(Vec3 position, double scale) {
        return new Vec3(position.x * scale, position.y, position.z * scale);
    }

    /** 把delta的X/Z按scale缩放，加targetOrigin，Y加delta.y与yOffset。 */
    public static Vec3 mapRelative(Vec3 delta, Vec3 targetOrigin, double scale, double yOffset) {
        return targetOrigin.add(delta.x * scale, delta.y + yOffset, delta.z * scale);
    }

    /** 解析后的滤镜物品ID和目标维度ID。 */
    public record Route(ResourceLocation filter, ResourceLocation dimension) {
        /** 滤镜物品ID和目标维度ID均不可为空。 */
        public Route {
            Objects.requireNonNull(filter, "filter");
            Objects.requireNonNull(dimension, "dimension");
        }
    }
}

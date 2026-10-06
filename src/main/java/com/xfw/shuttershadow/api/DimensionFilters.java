package com.xfw.shuttershadow.api;

import com.xfw.shuttershadow.DimensionCameraConfig;
import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** 公开滤镜组件/来源路由/坐标映射API。 */
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

    /** 委托DimensionCameraConfig以注册表优先、本地JSON兜底解析路由。 */
    public static @Nullable Route resolve(@Nullable RegistryAccess registries, ItemStack filter,
                                         ResourceLocation sourceDimension) {
        return DimensionCameraConfig.resolve(registries, filter, sourceDimension);
    }

    /** 路由有有限比例时以来源维度类型比例除该值，否则用原版两世界传送比例。 */
    public static double horizontalScale(@Nullable Route route, Level source, Level target) {
        return route != null && Double.isFinite(route.coordinateScale())
                ? source.dimensionType().coordinateScale() / route.coordinateScale()
                : DimensionType.getTeleportationScale(source.dimensionType(), target.dimensionType());
    }

    /** 按scale缩放X/Z，Y保持原值。 */
    public static Vec3 mapAbsolute(Vec3 position, double scale) {
        return new Vec3(position.x * scale, position.y, position.z * scale);
    }

    /** 把delta的X/Z按scale缩放，加targetOrigin，Y加delta.y与yOffset。 */
    public static Vec3 mapRelative(Vec3 delta, Vec3 targetOrigin, double scale, double yOffset) {
        return targetOrigin.add(delta.x * scale, delta.y + yOffset, delta.z * scale);
    }

    /** 解析后的滤镜物品ID、目标维度ID及目标比例记录。 */
    public record Route(ResourceLocation filter, ResourceLocation dimension, double coordinateScale) {
        /** 紧凑构造器要求filter/dimension非null，比例必须正有限或NaN自动标记。 */
        public Route {
            Objects.requireNonNull(filter, "filter");
            Objects.requireNonNull(dimension, "dimension");
            if (!Double.isNaN(coordinateScale)
                    && (!Double.isFinite(coordinateScale) || coordinateScale <= 0.0D)) {
                throw new IllegalArgumentException("coordinateScale must be positive and finite, or NaN");
            }
        }
    }
}

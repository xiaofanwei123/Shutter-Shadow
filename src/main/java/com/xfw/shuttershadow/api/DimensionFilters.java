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

/** 维度滤镜、数据包路由与坐标换算的公共入口；不启动相机或加载区块。 */
public final class DimensionFilters {
    private DimensionFilters() {}

    /** 创建指定目标的滤镜；resolve 检查路由，目标世界是否存在由调用者检查。 */
    public static ItemStack create(ResourceLocation targetDimension) {
        ItemStack stack = Shuttershadow.DIMENSION_FILTER.get().getDefaultInstance();
        stack.set(Shuttershadow.DIMENSION_FILTER_TARGET.get(), Objects.requireNonNull(targetDimension));
        return stack;
    }

    /** 读取本模组滤镜携带的目标；普通滤镜或未指定目标时返回 null。 */
    public static @Nullable ResourceLocation target(ItemStack filter) {
        return filter != null && filter.is(Shuttershadow.DIMENSION_FILTER.get())
                ? filter.get(Shuttershadow.DIMENSION_FILTER_TARGET.get()) : null;
    }

    /**
     * 解析当前来源的实际路由；支持 Exposure 数据包中的自定义滤镜谓词。
     * 注册表未准备好或没有匹配谓词时沿用本模组的本地配置回退。
     * 路由不保证目标世界已注册或区块已加载，调用者应自行检查。
     */
    public static @Nullable Route resolve(@Nullable RegistryAccess registries, ItemStack filter,
                                         ResourceLocation sourceDimension) {
        return DimensionCameraConfig.resolve(registries, filter, sourceDimension);
    }

    /** 返回实际的 X/Z 倍率；省略数据包 coordinate_scale 时使用目标维度类型。 */
    public static double horizontalScale(@Nullable Route route, Level source, Level target) {
        return route != null && Double.isFinite(route.coordinateScale())
                ? source.dimensionType().coordinateScale() / route.coordinateScale()
                : DimensionType.getTeleportationScale(source.dimensionType(), target.dimensionType());
    }

    /** 绝对坐标只缩放 X/Z，保留脚下的 Y 坐标；scale 使用 horizontalScale 的结果。 */
    public static Vec3 mapAbsolute(Vec3 position, double scale) {
        return new Vec3(position.x * scale, position.y, position.z * scale);
    }

    /** 映射相对于源基准的位移；Y 只叠加位移和相机高度偏移。 */
    public static Vec3 mapRelative(Vec3 delta, Vec3 targetOrigin, double scale, double yOffset) {
        return targetOrigin.add(delta.x * scale, delta.y + yOffset, delta.z * scale);
    }

    /**
     * 一个已选中的不可变路由。filter 是滤镜物品注册 ID，不是 Exposure 数据条目 ID。
     * coordinateScale 是目标坐标比例（下界为 8），
     * 而非实际 X/Z 倍率；NaN 表示采用已注册的目标维度类型。
     */
    public record Route(ResourceLocation filter, ResourceLocation dimension, double coordinateScale) {
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

package com.xfw.shuttershadow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.mortuusars.exposure.data.Filter;
import net.minecraft.advancements.critereon.ItemSubPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exposure物品子谓词携带的来源维度→目标路由映射。 */
public record DimensionCameraPredicate(Map<ResourceLocation, Route> routes)
        implements ItemSubPredicate {
    private static final Codec<Double> COORDINATE_SCALE_CODEC = Codec.DOUBLE.validate(scale ->
            Double.isFinite(scale) && scale > 0.0D ? DataResult.success(scale)
                    : DataResult.error(() -> "camera route coordinate_scale must be positive and finite"));

    public static final Codec<Route> ROUTE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("target_dimension")
                    .forGetter(Route::targetDimension),
            COORDINATE_SCALE_CODEC.optionalFieldOf("coordinate_scale", Double.NaN)
                    .forGetter(Route::coordinateScale)
    ).apply(instance, Route::new));

    public static final Codec<DimensionCameraPredicate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(ResourceLocation.CODEC, ROUTE_CODEC).validate(routes ->
                            routes.isEmpty() ? DataResult.error(() -> "camera predicate routes cannot be empty")
                                    : DataResult.success(routes))
                    .fieldOf("routes")
                    .forGetter(DimensionCameraPredicate::routes)
    ).apply(instance, DimensionCameraPredicate::new));

    /** 紧凑构造器拒绝空路由和null项，并复制为保持顺序的只读Map。 */
    public DimensionCameraPredicate {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("camera predicate routes cannot be empty");
        }
        Map<ResourceLocation, Route> copy = new LinkedHashMap<>();
        routes.forEach((source, route) -> {
            if (source == null || route == null) {
                throw new IllegalArgumentException("camera predicate route cannot be null");
            }
            copy.put(source, route);
        });
        routes = Collections.unmodifiableMap(copy);
    }

    /** 按来源维度查路由。 */
    public Route routeFor(ResourceLocation sourceDimension) {
        return sourceDimension == null ? null : routes.get(sourceDimension);
    }

    /** 从Filter的子谓词集合找到本类型，找不到返回null。 */
    public static @Nullable DimensionCameraPredicate from(Filter filter) {
        for (ItemSubPredicate predicate : filter.predicate().subPredicates().values()) {
            if (predicate instanceof DimensionCameraPredicate camera) return camera;
        }
        return null;
    }

    /** 始终返回true：此子谓词负责携带路由，物品/组件筛选由外层Exposure谓词负责。 */
    @Override
    public boolean matches(ItemStack stack) {
        // 外层物品谓词负责物品和组件匹配，
        // 此谓词仅携带维度路由数据。
        return true;
    }

    /** 单条目标维度及比例记录。 */
    public record Route(ResourceLocation targetDimension, double coordinateScale) {
        /** 紧凑构造器拒绝null目标以及非正/无穷比例，允许NaN作为自动比例标记。 */
        public Route {
            if (targetDimension == null) {
                throw new IllegalArgumentException("camera route target dimension cannot be null");
            }
            if (!Double.isNaN(coordinateScale)
                    && (!Double.isFinite(coordinateScale) || coordinateScale <= 0.0D)) {
                throw new IllegalArgumentException("camera route coordinate_scale must be positive");
            }
        }
    }
}

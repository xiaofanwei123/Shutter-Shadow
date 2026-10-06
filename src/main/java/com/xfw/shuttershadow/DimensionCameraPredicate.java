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

/**
 * Directional routes supplied by an Exposure filter data pack entry.
 *
 * <p>The map key is the dimension in which the camera is currently open. The
 * route value describes the dimension rendered by that filter from that
 * source. Keeping the source in the predicate is necessary because the same
 * target filter has a different coordinate conversion when viewed from
 * different dimensions.</p>
 */
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

    /** Returns the route for the player's current dimension, or null if absent. */
    public Route routeFor(ResourceLocation sourceDimension) {
        return sourceDimension == null ? null : routes.get(sourceDimension);
    }

    /** 创造栏和路由解析共用同一个维度谓词提取方法。 */
    public static @Nullable DimensionCameraPredicate from(Filter filter) {
        for (ItemSubPredicate predicate : filter.predicate().subPredicates().values()) {
            if (predicate instanceof DimensionCameraPredicate camera) return camera;
        }
        return null;
    }

    @Override
    public boolean matches(ItemStack stack) {
        // The surrounding ItemPredicate's items/components fields perform the
        // actual stack check. This predicate only carries route metadata.
        return true;
    }

    /** One source-dimension to target-dimension camera route. */
    public record Route(ResourceLocation targetDimension, double coordinateScale) {
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

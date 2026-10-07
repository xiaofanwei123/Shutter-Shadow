package com.xfw.shuttershadow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.mortuusars.exposure.data.Filter;
import net.minecraft.advancements.critereon.ItemSubPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 为滤镜声明来源维度和各自比例，目标由物品组件统一指定。 */
public record DimensionCameraPredicate(List<Source> routes)
        implements ItemSubPredicate {
    private static final Codec<Double> COORDINATE_SCALE_CODEC = Codec.DOUBLE.validate(scale ->
            Double.isFinite(scale) && scale > 0.0D ? DataResult.success(scale)
                    : DataResult.error(() -> "camera route coordinate_scale must be positive and finite"));

    public static final Codec<Source> SOURCE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("source_dimension")
                    .forGetter(Source::sourceDimension),
            COORDINATE_SCALE_CODEC.optionalFieldOf("coordinate_scale", 1.0D)
                    .forGetter(Source::coordinateScale)
    ).apply(instance, Source::new));

    public static final Codec<DimensionCameraPredicate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            SOURCE_CODEC.listOf().validate(routes -> hasUniqueSources(routes)
                            ? DataResult.success(routes)
                            : DataResult.error(() -> "camera predicate routes must contain unique source dimensions"))
                    .fieldOf("routes")
                    .forGetter(DimensionCameraPredicate::routes)
    ).apply(instance, DimensionCameraPredicate::new));

    /** 复制来源列表为只读快照，拒绝空列表或重复来源。 */
    public DimensionCameraPredicate {
        routes = List.copyOf(routes);
        if (!hasUniqueSources(routes)) {
            throw new IllegalArgumentException("camera predicate routes must contain unique source dimensions");
        }
    }

    /** 每个来源只允许配置一次，避免列表顺序改变路由含义。 */
    private static boolean hasUniqueSources(List<Source> routes) {
        return !routes.isEmpty()
                && routes.stream().map(Source::sourceDimension).distinct().count() == routes.size();
    }

    /** 读取当前来源维度的比例，未列出的来源不能使用此滤镜。 */
    public Source routeFor(ResourceLocation sourceDimension) {
        return sourceDimension == null ? null : routes.stream()
                .filter(route -> sourceDimension.equals(route.sourceDimension())).findFirst().orElse(null);
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

    /** 单条来源维度配置，坐标比例省略时默认为一。 */
    public record Source(ResourceLocation sourceDimension, double coordinateScale) {
        /** 来源不可为空，比例必须为正数且有限。 */
        public Source {
            if (sourceDimension == null) {
                throw new IllegalArgumentException("camera source dimension cannot be null");
            }
            if (!Double.isFinite(coordinateScale) || coordinateScale <= 0.0D) {
                throw new IllegalArgumentException("camera source coordinate_scale must be positive and finite");
            }
        }
    }
}

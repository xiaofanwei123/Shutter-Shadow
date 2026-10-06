package com.xfw.shuttershadow.client;

import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

import java.util.HashMap;
import java.util.Map;

/** Selects a resource-pack model by the dimension ID stored on a filter stack. */
public final class DimensionFilterModels {
    private static final String MODEL_DIRECTORY = "models/item/dimension_filter";
    private static final String MODEL_PREFIX = "item/dimension_filter/";
    private static Map<ResourceLocation, ModelResourceLocation> modelLocations = Map.of();

    private DimensionFilterModels() {
    }

    public static void registerModels(ModelEvent.RegisterAdditional event) {
        Map<ResourceLocation, ModelResourceLocation> found = new HashMap<>();
        Minecraft.getInstance().getResourceManager()
                .listResources(MODEL_DIRECTORY, id -> id.getNamespace().equals(Shuttershadow.MODID)
                        && id.getPath().endsWith(".json"))
                .keySet().forEach(id -> {
                    String path = id.getPath();
                    String relative = path.substring(MODEL_DIRECTORY.length() + 1, path.length() - ".json".length());
                    int separator = relative.indexOf('/');
                    if (separator < 1 || separator == relative.length() - 1) return;

                    ResourceLocation dimension = ResourceLocation.tryBuild(
                            relative.substring(0, separator), relative.substring(separator + 1));
                    if (dimension == null) return;

                    ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(
                            Shuttershadow.MODID, MODEL_PREFIX + relative);
                    ModelResourceLocation location = ModelResourceLocation.standalone(modelId);
                    event.register(location);
                    found.put(dimension, location);
                });
        modelLocations = Map.copyOf(found);
    }

    public static void selectModel(ModelEvent.ModifyBakingResult event) {
        ModelResourceLocation filterLocation = ModelResourceLocation.inventory(Shuttershadow.DIMENSION_FILTER.getId());
        BakedModel base = event.getModels().get(filterLocation);
        if (base == null) return;

        Map<ResourceLocation, BakedModel> variants = new HashMap<>();
        modelLocations.forEach((dimension, location) -> {
            BakedModel model = event.getModels().get(location);
            if (model != null) variants.put(dimension, model);
        });

        event.getModels().put(filterLocation, new BakedModelWrapper<>(base) {
            private final ItemOverrides overrides = new ItemOverrides() {
                @Override
                public BakedModel resolve(BakedModel model, ItemStack stack, ClientLevel level,
                        LivingEntity entity, int seed) {
                    ResourceLocation target = DimensionFilters.target(stack);
                    return variants.getOrDefault(target, base);
                }
            };

            @Override
            public ItemOverrides getOverrides() {
                return overrides;
            }
        });
    }
}

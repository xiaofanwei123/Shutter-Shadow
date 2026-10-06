package com.xfw.shuttershadow;

import com.mojang.logging.LogUtils;
import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.core.DimensionRuntime;
import com.xfw.shuttershadow.core.DimensionRuntimeClient;
import com.xfw.shuttershadow.core.CoreConfig;
import net.minecraft.advancements.critereon.ItemSubPredicate;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.Registries;
import org.slf4j.Logger;

@Mod(Shuttershadow.MODID)
public class Shuttershadow {
    public static final String MODID = "shuttershadow";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<ItemSubPredicate.Type<?>> ITEM_SUB_PREDICATES =
            DeferredRegister.create(Registries.ITEM_SUB_PREDICATE_TYPE, MODID);

    /** Target dimension stored on every dimension filter variant. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>>
            DIMENSION_FILTER_TARGET = DATA_COMPONENTS.registerComponentType(
                    "dimension_filter_target",
                    builder -> builder.persistent(ResourceLocation.CODEC)
                            .networkSynchronized(ResourceLocation.STREAM_CODEC));

    /** One Exposure filter item; its target dimension is supplied by the component above. */
    public static final DeferredItem<DimensionFilterItem> DIMENSION_FILTER = ITEMS.register(
            "dimension_filter", () -> new DimensionFilterItem(new Item.Properties().stacksTo(1)));
    /** A color film roll that opts into the physical player dimension-film transaction. */
    public static final DeferredItem<PlayerDimensionFilmRollItem> PLAYER_DIMENSION_FILM = ITEMS.register(
            "player_dimension_film", () -> new PlayerDimensionFilmRollItem(new Item.Properties().stacksTo(16)));
    /** A color film roll that transfers the first creature photographed in the target dimension. */
    public static final DeferredItem<MobDimensionFilmRollItem> MOB_DIMENSION_FILM = ITEMS.register(
            "mob_dimension_film", () -> new MobDimensionFilmRollItem(new Item.Properties().stacksTo(16)));

    /** Exposure predicate type carrying source-to-target camera routes. */
    public static final java.util.function.Supplier<ItemSubPredicate.Type<DimensionCameraPredicate>>
            DIMENSION_CAMERA_PREDICATE = ITEM_SUB_PREDICATES.register(
                    "camera_dimension", () -> new ItemSubPredicate.Type<>(DimensionCameraPredicate.CODEC));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
            CREATIVE_MODE_TABS.register("camera_filters",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.shuttershadow"))
                    .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                    .icon(() -> DimensionFilters.create(Level.OVERWORLD.location()))
                    .displayItems((parameters, output) -> {
                        parameters.holders().lookup(io.github.mortuusars.exposure.Exposure.Registries.FILTER)
                                .ifPresent(filters -> filters.listElements()
                                        .filter(holder -> holder.value().predicate().items()
                                                .map(items -> items.contains(DIMENSION_FILTER.get().builtInRegistryHolder()))
                                                .orElse(false))
                                        .map(holder -> DimensionCameraPredicate.from(holder.value()))
                                        .filter(java.util.Objects::nonNull)
                                        .flatMap(predicate -> predicate.routes().values().stream())
                                        .map(DimensionCameraPredicate.Route::targetDimension)
                                        .distinct()
                                        .forEach(target -> output.accept(DimensionFilters.create(target))));
                        output.accept(PLAYER_DIMENSION_FILM.get());
                        output.accept(MOB_DIMENSION_FILM.get());
                        parameters.holders().lookup(Registries.ENCHANTMENT).ifPresent(enchantments -> {
                            enchantments.get(CameraEnchantments.EXPOSURE_FAILURE).ifPresent(holder ->
                                    output.accept(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, 1))));
                            enchantments.get(CameraEnchantments.NARCISSISM).ifPresent(holder ->
                                    output.accept(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, 1))));
                        });
                    })
                    .build());

    public Shuttershadow(IEventBus modEventBus, ModContainer modContainer) {
        CoreConfig.register(modContainer, modEventBus);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                ShuttershadowConfig.SERVER_SPEC);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
                ShuttershadowConfig.CLIENT_SPEC);
        registerClientConfigScreen(modEventBus, modContainer);
        ITEMS.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ITEM_SUB_PREDICATES.register(modEventBus);

        // Embedded runtime bootstrap: all common hooks and payloads must be registered
        // before client-only remote-world/render initialization.
        DimensionRuntime.init(modEventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            DimensionRuntimeClient.init();
        }
    }

    /** Registers the config screen without loading client-only classes on a dedicated server. */
    private static void registerClientConfigScreen(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try {
            Class<?> client = Class.forName("com.xfw.shuttershadow.client.ShuttershadowClient");
            client.getMethod("init", IEventBus.class, ModContainer.class).invoke(null, modEventBus, modContainer);
        } catch (ClassNotFoundException ignored) {
            // Dedicated server: no client configuration screen is available.
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to register Shuttershadow config screen", exception);
        }
    }

}

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

/** NeoForge模组入口。 */
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

    /** 保存各维度滤镜变体的目标维度。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>>
            DIMENSION_FILTER_TARGET = DATA_COMPONENTS.registerComponentType(
                    "dimension_filter_target",
                    builder -> builder.persistent(ResourceLocation.CODEC)
                            .networkSynchronized(ResourceLocation.STREAM_CODEC));

    /** 所有维度滤镜共用一个物品，目标维度由组件指定。 */
    public static final DeferredItem<DimensionFilterItem> DIMENSION_FILTER = ITEMS.register(
            "dimension_filter", () -> new DimensionFilterItem(new Item.Properties().stacksTo(1)));
    /** 参与玩家跨维度传送事务的彩色胶卷。 */
    public static final DeferredItem<PlayerDimensionFilmRollItem> PLAYER_DIMENSION_FILM = ITEMS.register(
            "player_dimension_film", () -> new PlayerDimensionFilmRollItem(new Item.Properties().stacksTo(16)));
    /** 将目标维度首个出镜生物带回的彩色胶卷。 */
    public static final DeferredItem<MobDimensionFilmRollItem> MOB_DIMENSION_FILM = ITEMS.register(
            "mob_dimension_film", () -> new MobDimensionFilmRollItem(new Item.Properties().stacksTo(16)));

    /** 携带来源维度到目标维度路由的物品谓词类型。 */
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

    /** 注册COMMON内核、SERVER玩法、CLIENT个人偏好配置及物品等DeferredRegister，再启动通用内核与客户端内核。 */
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

        // 先注册通用运行时钩子与数据包，
        // 再初始化客户端远维度世界和渲染。
        DimensionRuntime.init(modEventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            DimensionRuntimeClient.init();
        }
    }

    /** 只在客户端反射调用ShuttershadowClient.init注册原生配置界面，防止专用服务端加载客户端类型。 */
    private static void registerClientConfigScreen(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try {
            Class<?> client = Class.forName("com.xfw.shuttershadow.client.ShuttershadowClient");
            client.getMethod("init", IEventBus.class, ModContainer.class).invoke(null, modEventBus, modContainer);
        } catch (ClassNotFoundException ignored) {
            // 专用服务端无需注册客户端配置界面。
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to register Shuttershadow config screen", exception);
        }
    }

}

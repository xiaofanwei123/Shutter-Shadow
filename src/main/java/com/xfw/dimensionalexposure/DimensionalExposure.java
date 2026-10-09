package com.xfw.dimensionalexposure;

import com.xfw.dimensionalexposure.camera.CameraEnchantments;
import com.xfw.dimensionalexposure.camera.CameraTeleportSafetyEffect;
import com.xfw.dimensionalexposure.item.DimensionFilterItem;
import com.xfw.dimensionalexposure.item.MobDimensionFilmRollItem;
import com.xfw.dimensionalexposure.item.PlayerDimensionFilmRollItem;

import com.mojang.logging.LogUtils;
import com.xfw.dimensionalexposure.api.DimensionFilters;
import com.xfw.dimensionalexposure.core.DimensionRuntime;
import com.xfw.dimensionalexposure.core.DimensionRuntimeClient;
import com.xfw.dimensionalexposure.core.CoreConfig;
import io.github.mortuusars.exposure.Exposure;
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
import net.minecraft.world.effect.MobEffect;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.List;

/** NeoForge模组入口。 */
@Mod(DimensionalExposure.MODID)
public class DimensionalExposure {
    public static final String MODID = "dimensional_exposure";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, MODID);

    /** 相机传送后的限时环境伤害保护效果。 */
    public static final DeferredHolder<MobEffect, CameraTeleportSafetyEffect> SAFE_DIMENSION_TELEPORT_EFFECT =
            MOB_EFFECTS.register("safe_dimension_teleport", CameraTeleportSafetyEffect::new);

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

    /** 注册维度滤镜、胶卷与相机附魔书的创造物品栏。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
            CREATIVE_MODE_TABS.register("camera_filters",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.dimensional_exposure"))
                    .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                    .icon(() -> DimensionFilters.create(Level.OVERWORLD.location()))
                    .displayItems(DimensionalExposure::displayCreativeItems)
                    .build());

    /** 从当前数据包生成去重滤镜变体，并添加胶卷和各级相机附魔书。 */
    private static void displayCreativeItems(CreativeModeTab.ItemDisplayParameters parameters,
                                            CreativeModeTab.Output output) {
        parameters.holders().lookup(Exposure.Registries.FILTER).ifPresent(filters -> {
            var targets = new LinkedHashSet<ResourceLocation>();
            filters.listElements().forEach(holder -> {
                var predicate = holder.value().predicate();
                if (predicate.items().filter(items -> items.contains(DIMENSION_FILTER)).isEmpty()) return;
                var target = predicate.components().asPatch().get(DIMENSION_FILTER_TARGET.get());
                if (target != null) target.ifPresent(targets::add);
            });
            targets.forEach(target -> output.accept(DimensionFilters.create(target)));
        });
        output.accept(PLAYER_DIMENSION_FILM);
        output.accept(MOB_DIMENSION_FILM);
        parameters.holders().lookup(Registries.ENCHANTMENT).ifPresent(enchantments -> {
            for (var key : List.of(CameraEnchantments.EXPOSURE_FAILURE, CameraEnchantments.NARCISSISM,
                    CameraEnchantments.SAFE_DIMENSION_TELEPORT)) {
                enchantments.get(key).ifPresent(holder -> {
                    for (int level = 1; level <= holder.value().getMaxLevel(); level++) {
                        output.accept(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, level)));
                    }
                });
            }
        });
    }

    /** 注册COMMON日志、SERVER玩法和加载、CLIENT渲染和个人偏好配置，再启动通用内核与客户端内核。 */
    public DimensionalExposure(IEventBus modEventBus, ModContainer modContainer) {
        CoreConfig.register(modContainer, modEventBus);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                DimensionalExposureConfig.SERVER_SPEC);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
                DimensionalExposureConfig.CLIENT_SPEC);
        registerClientConfigScreen(modEventBus, modContainer);
        ITEMS.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        MOB_EFFECTS.register(modEventBus);

        // 先注册通用运行时钩子与数据包，
        // 再初始化客户端远维度世界和渲染。
        DimensionRuntime.init(modEventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            DimensionRuntimeClient.init();
        }
    }

    /** 只在客户端反射调用DimensionalExposureClient.init注册原生配置界面，防止专用服务端加载客户端类型。 */
    private static void registerClientConfigScreen(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try {
            Class<?> client = Class.forName("com.xfw.dimensionalexposure.client.DimensionalExposureClient");
            client.getMethod("init", IEventBus.class, ModContainer.class).invoke(null, modEventBus, modContainer);
        } catch (ClassNotFoundException ignored) {
            // 专用服务端无需注册客户端配置界面。
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to register DimensionalExposure config screen", exception);
        }
    }

}

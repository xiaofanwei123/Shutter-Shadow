package com.xfw.shuttershadow.core;

import com.xfw.shuttershadow.ShuttershadowConfig;
import com.xfw.shuttershadow.util.ModLogging;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** 定义日志通用配置，并按所属类型应用内核配置。 */
public final class CoreConfig {
    public static final String FILE_NAME = "shuttershadow-core.toml";
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLE_LOGGING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ENABLE_LOGGING = builder
                .comment("统一开启或关闭本模组日志，默认关闭，开启时遵守日志级别，不影响游戏内警告。")
                .translation("shuttershadow.configuration.core.enableLogging")
                .define("enableLogging", false);
        SPEC = builder.build();
    }

    /** 工具类私有构造器。 */
    private CoreConfig() {}

    /** 注册日志通用配置，并监听各类内核配置的加载、重载与卸载。 */
    public static void register(ModContainer container, IEventBus eventBus) {
        ModLogging.setEnabled(false);
        container.registerConfig(ModConfig.Type.COMMON, SPEC, FILE_NAME);
        eventBus.addListener(ModConfigEvent.Loading.class, CoreConfig::apply);
        eventBus.addListener(ModConfigEvent.Reloading.class, CoreConfig::apply);
        eventBus.addListener(ModConfigEvent.Unloading.class, CoreConfig::apply);
    }

    /** 只读取当前事件所属配置，加载时应用设置，卸载时恢复默认。 */
    static void apply(ModConfigEvent event) {
        var spec = event.getConfig().getSpec();
        if (spec == SPEC) {
            ModLogging.setEnabled(SPEC.isLoaded() && ENABLE_LOGGING.get());
        } else if (spec == ShuttershadowConfig.CLIENT_SPEC) {
            CoreSettings.doCheckGlError = configuredOrDefault(ShuttershadowConfig.CLIENT_SPEC,
                    ShuttershadowConfig.DO_CHECK_GL_ERROR);
            CoreSettings.enableClientPerformanceAdjustment = configuredOrDefault(ShuttershadowConfig.CLIENT_SPEC,
                    ShuttershadowConfig.ENABLE_CLIENT_PERFORMANCE_ADJUSTMENT);
            CoreSettings.saveMemoryInBufferPack = configuredOrDefault(ShuttershadowConfig.CLIENT_SPEC,
                    ShuttershadowConfig.SAVE_MEMORY_IN_BUFFER_PACK);
        } else if (spec == ShuttershadowConfig.SERVER_SPEC) {
            CoreSettings.activeLoading = configuredOrDefault(ShuttershadowConfig.SERVER_SPEC,
                    ShuttershadowConfig.SERVER_SIDE_NORMAL_CHUNK_LOADING);
        } else {
            return;
        }
    }

    /** 读取已加载的布尔配置，尚未加载或卸载后使用默认值。 */
    private static boolean configuredOrDefault(ModConfigSpec spec, ModConfigSpec.BooleanValue value) {
        return spec.isLoaded() ? value.get() : value.getDefault();
    }

    /** 读取所有内核游戏内警告的统一开关。 */
    public static boolean shouldDisplayWarning() {
        return configuredOrDefault(ShuttershadowConfig.CLIENT_SPEC, ShuttershadowConfig.ENABLE_WARNING);
    }

}

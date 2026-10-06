package com.xfw.shuttershadow.core;

import com.xfw.shuttershadow.util.Helper;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** 本地维度内核配置，使用 NeoForge 原生持久化与配置界面，不随服务器同步。 */
public final class CoreConfig {
    public static final String FILE_NAME = "shuttershadow-core.toml";
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLE_CLIENT_PERFORMANCE_ADJUSTMENT;
    public static final ModConfigSpec.BooleanValue CLIENT_TOLERANT_VERSION_MISMATCH_WITH_SERVER;
    public static final ModConfigSpec.BooleanValue DO_CHECK_GL_ERROR;
    public static final ModConfigSpec.BooleanValue SAVE_MEMORY_IN_BUFFER_PACK;
    public static final ModConfigSpec.BooleanValue ENABLE_WARNING;
    public static final ModConfigSpec.BooleanValue SERVER_SIDE_NORMAL_CHUNK_LOADING;
    public static final ModConfigSpec.BooleanValue CHUNK_PACKET_DEBUG;
    public static final ModConfigSpec.BooleanValue ENABLE_REMOTE_CHUNK_LOADING;
    public static final ModConfigSpec.BooleanValue SERVER_TOLERANT_VERSION_MISMATCH_WITH_CLIENT;
    public static final ModConfigSpec.BooleanValue SERVER_REJECT_CLIENT_WITHOUT_SHUTTERSHADOW;
    public static final ModConfigSpec.BooleanValue SERVER_TELEPORT_LOGGING;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_WARNINGS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ENABLE_CLIENT_PERFORMANCE_ADJUSTMENT = define(builder, "enableClientPerformanceAdjustment", true,
                "Reduce live dimension view distance when the client is lagging.");
        CLIENT_TOLERANT_VERSION_MISMATCH_WITH_SERVER = define(builder, "clientTolerantVersionMismatchWithServer", false,
                "Allow connecting to a server with a different dimension protocol version.");
        DO_CHECK_GL_ERROR = define(builder, "doCheckGlError", false,
                "Check OpenGL errors for rendering diagnostics.");
        SAVE_MEMORY_IN_BUFFER_PACK = define(builder, "saveMemoryInBufferPack", false,
                "Use smaller initial chunk mesh buffers to reduce memory use.");
        ENABLE_WARNING = define(builder, "enableWarning", true,
                "Show dimension runtime warnings.");
        SERVER_SIDE_NORMAL_CHUNK_LOADING = define(builder, "serverSideNormalChunkLoading", true,
                "Keep remote camera chunk tickets active while their subscriptions are in use.");
        CHUNK_PACKET_DEBUG = define(builder, "chunkPacketDebug", false,
                "Log remote chunk packets for diagnostics.");
        ENABLE_REMOTE_CHUNK_LOADING = define(builder, "enableRemoteChunkLoading", true,
                "Enable additional chunk loading for dimension cameras.");
        SERVER_TOLERANT_VERSION_MISMATCH_WITH_CLIENT = define(builder, "serverTolerantVersionMismatchWithClient", false,
                "Allow clients with a different dimension protocol version.");
        SERVER_REJECT_CLIENT_WITHOUT_SHUTTERSHADOW = define(builder, "serverRejectClientWithoutShuttershadow", true,
                "Require clients to support Shuttershadow dimension networking.");
        SERVER_TELEPORT_LOGGING = define(builder, "serverTeleportLogging", false,
                "Log seamless dimension teleports for diagnostics.");
        DISABLED_WARNINGS = builder.comment("Warning IDs to hide, for example low_max_memory.")
                .translation("shuttershadow.configuration.core.disabledWarnings")
                .defineListAllowEmpty("disabledWarnings", List.of(), () -> "", value -> value instanceof String);
        SPEC = builder.build();
    }

    private CoreConfig() {}

    private static ModConfigSpec.BooleanValue define(ModConfigSpec.Builder builder, String key,
                                                     boolean defaultValue, String comment) {
        return builder.comment(comment).translation("shuttershadow.configuration.core." + key)
                .define(key, defaultValue);
    }

    /** 构造期只注册配置，真实值在 NeoForge 的加载及重新加载事件中应用。 */
    public static void register(ModContainer container, IEventBus eventBus) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC, FILE_NAME);
        eventBus.addListener(ModConfigEvent.Loading.class, CoreConfig::apply);
        eventBus.addListener(ModConfigEvent.Reloading.class, CoreConfig::apply);
    }

    static void apply(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        CoreSettings.doCheckGlError = DO_CHECK_GL_ERROR.get();
        CoreSettings.activeLoading = SERVER_SIDE_NORMAL_CHUNK_LOADING.get();
        CoreSettings.enableClientPerformanceAdjustment = ENABLE_CLIENT_PERFORMANCE_ADJUSTMENT.get();
        CoreSettings.chunkPacketDebug = CHUNK_PACKET_DEBUG.get();
        CoreSettings.saveMemoryInBufferPack = SAVE_MEMORY_IN_BUFFER_PACK.get();
        Helper.LOGGER.info("Shuttershadow dimension runtime config applied");
    }

    public static boolean shouldDisplayWarning(String warningKey) {
        return ENABLE_WARNING.get() && !DISABLED_WARNINGS.get().contains(warningKey);
    }

}

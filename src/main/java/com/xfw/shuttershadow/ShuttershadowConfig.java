package com.xfw.shuttershadow;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 分别定义服务端玩法与区块加载配置、客户端渲染与个人偏好配置。 */
public final class ShuttershadowConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.IntValue STAND_PLAYER_RADIUS;
    public static final ModConfigSpec.IntValue MOB_CAPTURE_RADIUS;
    public static final ModConfigSpec.IntValue MAX_REMOTE_VIEW_DISTANCE;
    public static final ModConfigSpec.BooleanValue ACCEPT_STAND_DIMENSION_FILM_TELEPORT;
    public static final ModConfigSpec.BooleanValue ENABLE_CLIENT_PERFORMANCE_ADJUSTMENT;
    public static final ModConfigSpec.BooleanValue DO_CHECK_GL_ERROR;
    public static final ModConfigSpec.BooleanValue SAVE_MEMORY_IN_BUFFER_PACK;
    public static final ModConfigSpec.BooleanValue ENABLE_WARNING;
    public static final ModConfigSpec.BooleanValue SERVER_SIDE_NORMAL_CHUNK_LOADING;
    public static final ModConfigSpec.BooleanValue ENABLE_REMOTE_CHUNK_LOADING;
    public static final ModConfigSpec.IntValue DELAY_UNLOAD_GENERATIONS;
    public static final ModConfigSpec.IntValue MAX_CACHED_VIEW_PRESETS;

    static {
        ModConfigSpec.Builder serverBuilder = new ModConfigSpec.Builder();
        serverBuilder.push("dimension_camera");
        MAX_REMOTE_VIEW_DISTANCE = serverBuilder
                .comment("Maximum target-dimension view distance for handheld and manually operated stand cameras, in chunks.",
                        "Default: 8. Allowed chunk radius: 3-32; radius 3 covers at most a 7x7 chunk window.",
                        "The actual distance also follows the client's requested distance and the server view-distance.",
                        "Applies to remote chunk subscriptions, live rendering and manual stand photos.",
                        "Does not change source-world group range, creature search range or destination teleport timing.")
                .translation("shuttershadow.configuration.max_remote_view_distance")
                .defineInRange("max_view_distance", 8, 3, 32);
        serverBuilder.pop();
        serverBuilder.push("camera_stand");
        STAND_PLAYER_RADIUS = serverBuilder
                .comment("Maximum distance for players in frame of a dimension camera stand.")
                .defineInRange("stand_player_radius", 8, 1, 64);
        serverBuilder.pop();
        serverBuilder.push("mob_dimension_film");
        MOB_CAPTURE_RADIUS = serverBuilder
                .comment("Entity search radius in target-dimension blocks for a dimension filter with creature dimension film.",
                        "Applies to handheld, manual stand and redstone stand photos.",
                        "Expands the camera block's search box by 1-32 blocks along each axis (default: 16).",
                        "Lens field of view, focal length and line of sight still determine which creatures are in frame.",
                        "This range does not determine the photo render distance.",
                        "Lower values reduce entity search cost.")
                .translation("shuttershadow.configuration.mob_capture_radius")
                .defineInRange("capture_radius", 16, 1, 32);
        serverBuilder.pop();
        serverBuilder.translation("shuttershadow.configuration.core").push("core");
        SERVER_SIDE_NORMAL_CHUNK_LOADING = defineCoreBoolean(serverBuilder, "serverSideNormalChunkLoading", true,
                "选择相机额外区块票据的活跃等级：开启时可更新实体与方块，关闭时仅要求方块更新。",
                "运行中更改后自动释放旧等级票据，并按新等级重新加载当前相机范围。");
        ENABLE_REMOTE_CHUNK_LOADING = defineCoreBoolean(serverBuilder, "enableRemoteChunkLoading", true,
                "是否为相机额外订阅的目标维度区块添加加载票据，不影响原版玩家区块加载。",
                "运行中关闭会释放相机票据，重新开启后自动重新加载仍在观察的范围。");
        DELAY_UNLOAD_GENERATIONS = serverBuilder
                .comment("相机停止观察区块后，保留额外区块订阅的更新代数，默认 4，范围 1-120。不建议修改。",
                        "单位是订阅更新代数，不是游戏刻或区块；每代约 13 游戏刻，实际清理发生在超过设定代数后。",
                        "数值越小越快释放区块，但重新观察时更容易重复加载；越大越占用内存。",
                        "加载超过 1200/2000 个区块时仍会提前缩短延迟；玩家重生或断线时仍立即清理记录。")
                .translation("shuttershadow.configuration.core.delayUnloadGenerations")
                .defineInRange("delayUnloadGenerations", 4, 1, 120);
        serverBuilder.pop();
        SERVER_SPEC = serverBuilder.build();

        ModConfigSpec.Builder clientBuilder = new ModConfigSpec.Builder();
        clientBuilder.push("camera_stand");
        ACCEPT_STAND_DIMENSION_FILM_TELEPORT = clientBuilder
                .comment("Allow dimension-film camera stands to teleport you when you are in frame.",
                        "This is a personal preference and is sent to the server while you are connected.")
                .define("accept_stand_dimension_film_teleport", true);
        clientBuilder.pop();
        clientBuilder.translation("shuttershadow.configuration.core").push("core");
        MAX_CACHED_VIEW_PRESETS = clientBuilder
                .comment("原版渲染器每个维度最多保留的完整视区索引数量，默认 3，范围 1-16，包含当前视区。",
                        "较小值减少索引缓存内存，较大值便于快速折返时复用索引；已有区段和缓冲继续独立复用。",
                        "运行中修改自动生效。使用 Sodium 时地形由其自行管理，此项影响很小。")
                .translation("shuttershadow.configuration.core.maxCachedViewPresets")
                .defineInRange("maxCachedViewPresets", 3, 1, 16);
        ENABLE_CLIENT_PERFORMANCE_ADJUSTMENT = defineCoreBoolean(clientBuilder, "enableClientPerformanceAdjustment", true,
                "客户端卡顿时缩短原版地形渲染的目标维度绘制距离，不修改普通世界视距。");
        DO_CHECK_GL_ERROR = defineCoreBoolean(clientBuilder, "doCheckGlError", false,
                "检查 OpenGL 错误以排查渲染问题，日志输出服从模组日志总开关。");
        SAVE_MEMORY_IN_BUFFER_PACK = defineCoreBoolean(clientBuilder, "saveMemoryInBufferPack", false,
                "减小原版区块网格缓冲区的初始分配，已有缓冲不受影响，修改后建议重启客户端。");
        ENABLE_WARNING = defineCoreBoolean(clientBuilder, "enableWarning", true,
                "统一开启或关闭所有内核游戏内警告，不影响日志总开关。");
        clientBuilder.pop();
        CLIENT_SPEC = clientBuilder.build();
    }

    /** 禁止实例化此工具类。 */
    private ShuttershadowConfig() {
    }

    /** 定义带说明和统一翻译键的内核布尔选项。 */
    private static ModConfigSpec.BooleanValue defineCoreBoolean(ModConfigSpec.Builder builder, String key,
                                                               boolean defaultValue, String... comments) {
        return builder.comment(comments).translation("shuttershadow.configuration.core." + key)
                .define(key, defaultValue);
    }

    /** 读取服务端支架玩家捕获半径（方块）。 */
    public static int standPlayerRadius() {
        return STAND_PLAYER_RADIUS.get();
    }

    /** 读取服务端最大远维度取景半径（区块）。 */
    public static int maxRemoteViewDistance() {
        return MAX_REMOTE_VIEW_DISTANCE.get();
    }

    /** 读取客户端是否接受支架玩家胶卷传送。 */
    public static boolean acceptStandDimensionFilmTeleport() {
        return ACCEPT_STAND_DIMENSION_FILM_TELEPORT.get();
    }

    /** 读取服务端生物捕获搜索盒半径（方块）。 */
    public static int mobCaptureRadius() {
        return MOB_CAPTURE_RADIUS.get();
    }

    /** 读取服务端停止观察后的区块订阅保留代数。 */
    public static int delayUnloadGenerations() {
        return DELAY_UNLOAD_GENERATIONS.get();
    }

    /** 读取客户端每个渲染器保留的完整视区索引上限。 */
    public static int maxCachedViewPresets() {
        return MAX_CACHED_VIEW_PRESETS.get();
    }
}

package com.xfw.shuttershadow;

import net.neoforged.neoforge.common.ModConfigSpec;

/** NeoForge SERVER配置控制目标取景上限、支架玩家范围及生物范围。 */
public final class ShuttershadowConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.IntValue STAND_PLAYER_RADIUS;
    public static final ModConfigSpec.IntValue MOB_CAPTURE_RADIUS;
    public static final ModConfigSpec.IntValue MAX_REMOTE_VIEW_DISTANCE;
    public static final ModConfigSpec.BooleanValue ACCEPT_STAND_DIMENSION_FILM_TELEPORT;

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
                        "Expands the camera block's search box by 1-16 blocks along each axis (default: 16).",
                        "Lens field of view, focal length and line of sight still determine which creatures are in frame.",
                        "This range does not determine the photo render distance.",
                        "Lower values reduce entity search cost.")
                .translation("shuttershadow.configuration.mob_capture_radius")
                .defineInRange("capture_radius", 16, 1, 16);
        serverBuilder.pop();
        SERVER_SPEC = serverBuilder.build();

        ModConfigSpec.Builder clientBuilder = new ModConfigSpec.Builder();
        clientBuilder.push("camera_stand");
        ACCEPT_STAND_DIMENSION_FILM_TELEPORT = clientBuilder
                .comment("Allow dimension-film camera stands to teleport you when you are in frame.",
                        "This is a personal preference and is sent to the server while you are connected.")
                .define("accept_stand_dimension_film_teleport", true);
        clientBuilder.pop();
        CLIENT_SPEC = clientBuilder.build();
    }

    /** 禁止实例化此工具类。 */
    private ShuttershadowConfig() {
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
}

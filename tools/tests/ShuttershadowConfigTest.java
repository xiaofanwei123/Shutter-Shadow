package com.xfw.shuttershadow;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 只初始化真实配置声明，不启动客户端、服务器或世界。 */
public final class ShuttershadowConfigTest {
    public static void main(String[] args) {
        ModConfigSpec.ValueSpec viewDistance = ShuttershadowConfig.SERVER_SPEC.getSpec()
                .get("dimension_camera.max_view_distance");
        check(viewDistance != null, "camera view limit is absent from the server config");
        check(viewDistance.getDefault().equals(8), "default camera view limit must be eight chunks");
        for (int value : new int[]{3, 8, 32}) {
            check(viewDistance.test(value), "valid camera view limit was rejected: " + value);
        }
        for (int value : new int[]{-1, 0, 1, 2, 33, 128}) {
            check(!viewDistance.test(value), "invalid camera view limit was accepted: " + value);
        }
        check(viewDistance.getTranslationKey().equals("shuttershadow.configuration.max_remote_view_distance"),
                "camera view limit has the wrong translation key");
        check(ShuttershadowConfig.CLIENT_SPEC.getSpec().get("dimension_camera.max_view_distance") == null,
                "server camera view limit was added to client preferences");
        ModConfigSpec.ValueSpec range = ShuttershadowConfig.SERVER_SPEC.getSpec()
                .get("mob_dimension_film.capture_radius");
        check(range != null, "creature range is absent from the server config");
        check(range.getDefault().equals(16), "default creature range must be 16 blocks");
        for (int value : new int[]{1, 8, 16}) {
            check(range.test(value), "valid range was rejected: " + value);
        }
        for (int value : new int[]{-1, 0, 17, 128, 512}) {
            check(!range.test(value), "invalid range was accepted: " + value);
        }
        check(range.getTranslationKey().equals("shuttershadow.configuration.mob_capture_radius"),
                "creature range has the wrong translation key");
        check(ShuttershadowConfig.CLIENT_SPEC.getSpec().get("mob_dimension_film.capture_radius") == null,
                "creature range was added to client preferences");
        check(ShuttershadowConfig.SERVER_SPEC.getSpec().get("camera_stand.stand_player_radius") != null,
                "stand player transfer range disappeared");
        check(ShuttershadowConfig.CLIENT_SPEC.getSpec().get("camera_stand.accept_stand_dimension_film_teleport") != null,
                "personal stand transfer preference disappeared");
        for (String removed : new String[]{"redstone_dimension_capture", "redstone_capture_chunk_radius",
                "redstone_teleport_chunk_radius"}) {
            check(ShuttershadowConfig.SERVER_SPEC.getSpec().get("camera_stand." + removed) == null,
                    "removed remote redstone capture setting remains: " + removed);
        }
        check(ShuttershadowConfig.SERVER_SPEC.getSpec().get("dimension_camera.use_shaders") == null,
                "dimension view shader option must be removed from server config");
        check(ShuttershadowConfig.CLIENT_SPEC.getSpec().get("dimension_camera.use_shaders") == null,
                "dimension view shader option must be absent from client config");
        check(ShuttershadowConfig.SERVER_SPEC.getSpec().get("camera_stand.redstone_capture_shaders") == null,
                "redstone shader option must be removed");
        System.out.println("PASS: camera view limit (default 8, range 3-32), creature range, preserved transfer settings and removed redstone remote capture settings");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package com.xfw.shuttershadow.util;

// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.blaze3d.platform.GlUtil;
import net.minecraft.network.chat.Component;

/** 客户端硬件检测及配置警告文本。 */
public class WorldContextHelper {
    public static Component getDisableWarningText(String warningKey) {
        return Component.literal(" ").append(
            Component.translatable("shuttershadow.core.warning_config_hint", warningKey)
        ).append(" ");
    }

    public static boolean isNvidiaVideocard() {
        return GlUtil.getVendor().toLowerCase().contains("nvidia");
    }

}

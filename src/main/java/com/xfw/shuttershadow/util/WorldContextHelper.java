package com.xfw.shuttershadow.util;



import com.mojang.blaze3d.platform.GlUtil;
import net.minecraft.network.chat.Component;

/** 提供警告配置提示和显卡厂商检测。 */
public class WorldContextHelper {
    /** 创建包含警告编号的配置提示文本。 */
    public static Component getDisableWarningText(String warningKey) {
        return Component.literal(" ").append(
            Component.translatable("shuttershadow.core.warning_config_hint", warningKey)
        ).append(" ");
    }

    /** 判断当前显卡厂商是否为英伟达。 */
    public static boolean isNvidiaVideocard() {
        return GlUtil.getVendor().toLowerCase().contains("nvidia");
    }

}

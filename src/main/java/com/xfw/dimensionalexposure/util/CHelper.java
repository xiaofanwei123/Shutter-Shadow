package com.xfw.dimensionalexposure.util;

import com.xfw.dimensionalexposure.DimensionalExposure;
import com.xfw.dimensionalexposure.core.CoreSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.opengl.GL11;


import static org.lwjgl.opengl.GL11.GL_NO_ERROR;

// 仅供客户端使用。
/** 提供客户端图形检查和聊天提示。 */
public class CHelper {
    
    private static int reportedErrorNum = 0;
    
    /** 配置启用且累计错误未超100时检查OpenGL，并记录异常。 */
    public static void checkGlError() {
        if (!CoreSettings.doCheckGlError) {
            return;
        }
        if (reportedErrorNum > 100) {
            return;
        }
        int errorCode = GL11.glGetError();
        if (errorCode != GL_NO_ERROR) {
            DimensionalExposure.LOGGER.error("OpenGL Error {}", errorCode, new Throwable());
            reportedErrorNum++;
        }
    }
    
    /** 向客户端聊天栏添加消息。 */
    public static void printChat(Component text) {
        Minecraft.getInstance().gui.getChat().addMessage(text);
    }

    /** 创建关闭所有内核警告的配置提示文本。 */
    public static Component getDisableWarningText() {
        return Component.literal(" ").append(
            Component.translatable("dimensional_exposure.core.warning_config_hint")
        ).append(" ");
    }

    /** 创建带下划线的可点击链接文本。 */
    public static MutableComponent getLinkText(String link) {
        return Component.literal(link).withStyle(
            style -> style.withClickEvent(new ClickEvent(
                ClickEvent.Action.OPEN_URL, link
            )).withUnderlined(true)
        );
    }
    
}

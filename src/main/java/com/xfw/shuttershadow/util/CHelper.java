package com.xfw.shuttershadow.util;

import com.xfw.shuttershadow.core.CoreSettings;


import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;


import static org.lwjgl.opengl.GL11.GL_NO_ERROR;

// 仅供客户端使用。
// Shuttershadow 第二轮裁剪：移除仅供维度堆叠选择界面使用的图标查询。
/** 提供客户端图形错误检查和聊天消息输出。 */
public class CHelper {
    
    private static int reportedErrorNum = 0;
    
    /** 只在配置启用且累计错误未超100时调用真实检查。 */
    public static void checkGlError() {
        if (!CoreSettings.doCheckGlError) {
            return;
        }
        if (reportedErrorNum > 100) {
            return;
        }
        doCheckGlError();
    }
    
    /** 调用glGetError，非零时记录代码与调用栈并增加累计错误数。 */
    public static void doCheckGlError() {
        int errorCode = GL11.glGetError();
        if (errorCode != GL_NO_ERROR) {
            Helper.err("OpenGL Error" + errorCode);
            new Throwable().printStackTrace();
            reportedErrorNum++;
        }
    }
    
    /** 向客户端聊天栏添加消息。 */
    public static void printChat(Component text) {
        Minecraft.getInstance().gui.getChat().addMessage(text);
    }
    
}

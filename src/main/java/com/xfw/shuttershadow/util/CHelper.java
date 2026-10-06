package com.xfw.shuttershadow.util;

import com.xfw.shuttershadow.core.CoreSettings;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;


import static org.lwjgl.opengl.GL11.GL_NO_ERROR;

//@OnlyIn(Dist.CLIENT)
// Shuttershadow 第二轮裁剪：移除仅供维度堆叠选择界面使用的图标查询。
public class CHelper {
    
    private static int reportedErrorNum = 0;
    
    public static void checkGlError() {
        if (!CoreSettings.doCheckGlError) {
            return;
        }
        if (reportedErrorNum > 100) {
            return;
        }
        doCheckGlError();
    }
    
    public static void doCheckGlError() {
        int errorCode = GL11.glGetError();
        if (errorCode != GL_NO_ERROR) {
            Helper.err("OpenGL Error" + errorCode);
            new Throwable().printStackTrace();
            reportedErrorNum++;
        }
    }
    
    public static void printChat(Component text) {
        Minecraft.getInstance().gui.getChat().addMessage(text);
    }
    
}

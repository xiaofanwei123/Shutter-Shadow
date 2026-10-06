package com.xfw.shuttershadow.core;

import com.xfw.shuttershadow.util.CHelper;
import com.xfw.shuttershadow.util.McHelper;
import com.xfw.shuttershadow.util.WorldContextHelper;
// Shuttershadow phase seven: relocated into the camera core.

import com.xfw.shuttershadow.event.ClientCleanupEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import com.xfw.shuttershadow.compat.IrisInterface;
import com.xfw.shuttershadow.compat.SodiumInterface;
import com.xfw.shuttershadow.core.GcMonitor;
import com.xfw.shuttershadow.network.CoreNetworkHandshake;
import com.xfw.shuttershadow.core.render.RemoteViewArea;
import com.xfw.shuttershadow.core.render.VisibleSectionDiscovery;
import com.xfw.shuttershadow.util.MyTaskList;
import com.xfw.shuttershadow.util.Helper;

// Shuttershadow 第三轮修改：撤销客户端调试命令注册，保留远程世界与渲染初始化顺序。
// Shuttershadow 第五轮裁剪：撤销门户动画和无效平台提示，保留相机多世界渲染初始化。
// Shuttershadow 第六轮：仅初始化相机多世界运行与仍有消费者的通用工具。
public class DimensionRuntimeClient {
    
    private static void showNvidiaVideoCardWarning() {
        CoreSettings.CLIENT_TASK_LIST.addTask(MyTaskList.withDelayCondition(
            () -> Minecraft.getInstance().level == null,
            MyTaskList.oneShotTask(() -> {
                if (CoreConfig.shouldDisplayWarning("nvidia") && WorldContextHelper.isNvidiaVideocard()) {
                    if (!SodiumInterface.invoker.isSodiumPresent()) {
                        CHelper.printChat(
                            Component.translatable("shuttershadow.core.nvidia_warning")
                                .withStyle(ChatFormatting.RED)
                                .append(McHelper.getLinkText("https://github.com/CaffeineMC/sodium-fabric/issues/1486"))
                        );
                    }
                }
            })
        ));
    }
    
    
    public static void init() {
        ClientWorldLoader.init();
        
        
        
        GcMonitor.initClient();

//        showIntelVideoCardWarning();
        
        showNvidiaVideoCardWarning();
        
        
        
        VisibleSectionDiscovery.init();
        
        RemoteViewArea.init();
        
    
        CoreNetworkHandshake.initClient();
        
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, e -> {
            CoreSettings.CLIENT_TASK_LIST.forceClearTasks();
            com.xfw.shuttershadow.network.PacketRedirectionClient.resetChunkBatchCalculator();
        });

        boolean isSodiumPresent =
            ModList.get().isLoaded("sodium");
        if (isSodiumPresent) {
            Helper.log("Sodium is present");
            
            SodiumInterface.invoker = new SodiumInterface.OnSodiumPresent();
            
            // Sodium compat is pretty ok now. No warning needed.
//            CoreSettings.clientTaskList.addTask(MyTaskList.oneShotTask(() -> {
//                if (CoreSettings.enableWarning) {
//                    CHelper.printChat(
//                        Component.translatable("shuttershadow.core.sodium_warning")
//                            .append(WorldContextHelper.getDisableWarningText())
//                    );
//                }
//            }));
        }
        else {
            Helper.log("Sodium is not present");
        }
        
        if (ModList.get().isLoaded("iris")) {
            Helper.log("Iris is present");
            IrisInterface.invoker = new IrisInterface.OnIrisPresent();
            
            CoreSettings.CLIENT_TASK_LIST.addTask(MyTaskList.oneShotTask(() -> {
                if (CoreConfig.shouldDisplayWarning("iris")) {
                    CHelper.printChat(
                        Component.translatable("shuttershadow.core.iris_warning")
                            .append(WorldContextHelper.getDisableWarningText("iris"))
                    );
                }
            }));
        }
        else {
            Helper.log("Iris is not present");
        }

    }
    
}

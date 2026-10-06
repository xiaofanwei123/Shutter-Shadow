package com.xfw.shuttershadow.core;

import com.xfw.shuttershadow.util.CHelper;
import com.xfw.shuttershadow.util.McHelper;
import com.xfw.shuttershadow.util.WorldContextHelper;


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
/** 初始化客户端维度内核及可选渲染适配器。 */
public class DimensionRuntimeClient {
    
    /** 在进入世界后执行一次硬件提示任务。 */
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
    
    
    /** 初始化多世界渲染、内存监测、可见区段及网络握手。 */
    public static void init() {
        ClientWorldLoader.init();
        
        
        
        GcMonitor.initClient();

// 已停用的英特尔显卡提示。
        
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
            
            // 钠兼容已可正常使用，无需额外提示。
// 已停用的钠兼容提示任务。
// 原逻辑仅在允许显示提醒时执行。
// 原逻辑向玩家聊天栏显示提醒。
// 原逻辑使用本模组的钠提醒翻译文本。
// 原逻辑附带关闭提醒的说明。
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

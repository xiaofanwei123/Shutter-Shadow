package com.xfw.shuttershadow.core;


import com.xfw.shuttershadow.util.CHelper;


import com.xfw.shuttershadow.event.ClientCleanupEvent;
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

/** 初始化客户端维度内核及可选渲染适配器。 */
public class DimensionRuntimeClient {
    
    /** 初始化多世界渲染、内存监测、可见区段及连接就绪清理。 */
    public static void init() {
        ClientWorldLoader.init();
        GcMonitor.initClient();
        VisibleSectionDiscovery.init();
        RemoteViewArea.init();
        CoreNetworkHandshake.initClient();
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, e -> {
            CoreSettings.CLIENT_TASK_LIST.forceClearTasks();
            com.xfw.shuttershadow.network.PacketRedirectionClient.resetChunkBatchCalculator();
        });

        if (ModList.get().isLoaded("sodium")) {
            SodiumInterface.invoker = new SodiumInterface.OnSodiumPresent();
        }
        
        if (ModList.get().isLoaded("iris")) {
            IrisInterface.invoker = new IrisInterface.OnIrisPresent();
            
            CoreSettings.CLIENT_TASK_LIST.addTask(MyTaskList.oneShotTask(() -> {
                if (CoreConfig.shouldDisplayWarning()) {
                    CHelper.printChat(
                        Component.translatable("shuttershadow.core.iris_warning")
                            .append(CHelper.getDisableWarningText())
                    );
                }
            }));
        }

    }
    
}

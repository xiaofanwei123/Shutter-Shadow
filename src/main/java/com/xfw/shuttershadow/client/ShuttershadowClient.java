package com.xfw.shuttershadow.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** 注册客户端NeoForge原生配置屏与滤镜模型事件，无Cloth Config依赖。 */
public final class ShuttershadowClient {
    /** 禁止实例化此工具类。 */
    private ShuttershadowClient() {
    }

    /** 注册ConfigurationScreen工厂，并把模型扫描与烘焙包装挂到MOD事件总线。 */
    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> new ConfigurationScreen(mod, parent));
        modEventBus.addListener(DimensionFilterModels::registerModels);
        modEventBus.addListener(DimensionFilterModels::selectModel);
    }
}

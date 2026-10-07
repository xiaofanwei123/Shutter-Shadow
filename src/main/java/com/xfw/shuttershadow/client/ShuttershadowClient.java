package com.xfw.shuttershadow.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** 注册客户端原生配置屏、滤镜模型和相机附件提示框。 */
public final class ShuttershadowClient {
    /** 禁止实例化此工具类。 */
    private ShuttershadowClient() {
    }

    /** 注册配置屏、模型事件和原生物品提示框组件。 */
    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> new ConfigurationScreen(mod, parent));
        modEventBus.addListener(DimensionFilterModels::registerModels);
        modEventBus.addListener(DimensionFilterModels::selectModel);
        modEventBus.addListener(CameraAttachmentTooltip::register);
        NeoForge.EVENT_BUS.addListener(CameraAttachmentTooltip::gather);
    }
}

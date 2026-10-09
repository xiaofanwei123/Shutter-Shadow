package com.xfw.dimensionalexposure.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** 注册客户端配置屏、滤镜模型和附件提示框。 */
public final class DimensionalExposureClient {
    /** 禁止实例化此工具类。 */
    private DimensionalExposureClient() {
    }

    /** 注册配置屏、模型事件和物品提示框。 */
    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> new ConfigurationScreen(mod, parent));
        modEventBus.addListener(DimensionFilterModels::registerModels);
        modEventBus.addListener(DimensionFilterModels::selectModel);
        modEventBus.addListener(CameraAttachmentTooltip::register);
        NeoForge.EVENT_BUS.addListener(CameraAttachmentTooltip::gather);
    }
}

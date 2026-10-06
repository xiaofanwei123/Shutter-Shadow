package com.xfw.shuttershadow.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only integration for the configuration screen. */
public final class ShuttershadowClient {
    private ShuttershadowClient() {
    }

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> new ConfigurationScreen(mod, parent));
        modEventBus.addListener(DimensionFilterModels::registerModels);
        modEventBus.addListener(DimensionFilterModels::selectModel);
    }
}

package com.xfw.shuttershadow.core.render;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.xfw.shuttershadow.core.ClientWorldLoader;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link FogRenderer}
 * {@link com.xfw.shuttershadow.mixin.minecraft.client.MixinFogRenderer}
 */
@SuppressWarnings("SpellCheckingInspection")
public class FogRendererContext {
    public float red;
    public float green;
    public float blue;
    public int targetBiomeFog = -1;
    public int previousBiomeFog = -1;
    public long biomeChangedTime = -1L;
    
    public static Consumer<FogRendererContext> copyContextFromObject;
    public static Consumer<FogRendererContext> copyContextToObject;
    public static Supplier<Vec3> getCurrentFogColor;
    
    public static StaticFieldsSwappingManager<FogRendererContext> swappingManager;
    
    public static void init() {
        //load the class and apply mixin
        FogRenderer.class.hashCode();
        
        swappingManager = new StaticFieldsSwappingManager<>(
            copyContextFromObject, copyContextToObject,
            FogRendererContext::new
        );
        // 换维沿用当前连接的雾上下文；断开连接时只丢弃已缓存的维度记录。
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
            event -> swappingManager.contextMap.clear());
        
    }
    
    public static void update() {
        swappingManager.setOuterDimension(RenderStates.originalPlayerDimension);
        if (ClientWorldLoader.getIsInitialized()) {
            ClientWorldLoader.getClientWorlds().forEach(world -> {
                ResourceKey<Level> dimension = world.dimension();
                swappingManager.contextMap.computeIfAbsent(
                    dimension,
                    k -> new StaticFieldsSwappingManager.ContextRecord<>(
                        dimension,
                        new FogRendererContext()
                    )
                );
            });
        }
    }
    
    public static void onPlayerTeleport(ResourceKey<Level> from, ResourceKey<Level> to) {
        swappingManager.updateOuterDimensionAndChangeContext(to);
    }
    
}

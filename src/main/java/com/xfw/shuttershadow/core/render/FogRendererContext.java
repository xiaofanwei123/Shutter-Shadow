package com.xfw.shuttershadow.core.render;


import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.xfw.shuttershadow.core.ClientWorldLoader;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** FogRenderer静态颜色与群系雾插值数据的每维度上下文。 */
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
    
    /** 触发FogRenderer类初始化，建立交换器并在登出清上下文表。 */
    public static void init() {
        // 触发类加载，使雾渲染混入生效。
        FogRenderer.class.hashCode();
        
        swappingManager = new StaticFieldsSwappingManager<>(
            copyContextFromObject, copyContextToObject,
            FogRendererContext::new
        );
        // 换维沿用当前连接的雾上下文；断开连接时只丢弃已缓存的维度记录。
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
            event -> swappingManager.contextMap.clear());
        
    }
    
    /** 指定本帧真实来源维度，并为当前缓存世界懒建立雾上下文。 */
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
    
    /** 玩家真实传送时切换外层雾上下文到目标维度。 */
    public static void onPlayerTeleport(ResourceKey<Level> from, ResourceKey<Level> to) {
        swappingManager.updateOuterDimensionAndChangeContext(to);
    }
    
}

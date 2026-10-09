package com.xfw.dimensionalexposure.core.render;


import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
    
    private static final Map<ResourceKey<Level>, FogRendererContext> contexts = new HashMap<>();
    private static final ArrayDeque<ResourceKey<Level>> dimensions = new ArrayDeque<>();
    private static ResourceKey<Level> outerDimension;
    private static boolean initialized;
    
    /** 仅登记一次断线清理，换维和重复初始化沿用当前连接的雾状态。 */
    public static void init() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
            event -> {
                contexts.clear();
                dimensions.clear();
                outerDimension = null;
            });
    }
    
    /** 指定本帧真实来源维度；仅实际渲染或传送时建立雾记录。 */
    public static void update() {
        requireUnswapped();
        outerDimension = RenderStates.originalPlayerDimension;
    }

    /** 暂存当前维度的静态雾状态，并装入目标维度的独立记录。 */
    public static void push(ResourceKey<Level> dimension) {
        copyContextToObject.accept(context(currentDimension()));
        dimensions.push(dimension);
        copyContextFromObject.accept(context(dimension));
    }

    /** 保存本层远景雾状态，并恢复上一层或玩家世界的记录。 */
    public static void pop() {
        copyContextToObject.accept(context(dimensions.pop()));
        copyContextFromObject.accept(context(currentDimension()));
    }
    
    /** 玩家真实传送时切换外层雾上下文到目标维度。 */
    public static void onPlayerTeleport(ResourceKey<Level> from, ResourceKey<Level> to) {
        requireUnswapped();
        copyContextToObject.accept(context(from));
        copyContextFromObject.accept(context(to));
        outerDimension = to;
    }

    /** 取得当前渲染层维度，空堆栈时使用真实玩家维度。 */
    private static ResourceKey<Level> currentDimension() {
        return dimensions.isEmpty() ? Objects.requireNonNull(outerDimension) : dimensions.element();
    }

    /** 按维度懒建立独立的雾颜色和群系过渡记录。 */
    private static FogRendererContext context(ResourceKey<Level> dimension) {
        return contexts.computeIfAbsent(Objects.requireNonNull(dimension), key -> new FogRendererContext());
    }

    /** 禁止远景嵌套期间改变真实来源维度。 */
    private static void requireUnswapped() {
        if (!dimensions.isEmpty()) throw new IllegalStateException("不能在远景渲染期间切换玩家雾上下文");
    }
    
}

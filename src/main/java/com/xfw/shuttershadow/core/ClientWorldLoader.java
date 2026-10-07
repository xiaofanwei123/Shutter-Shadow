package com.xfw.shuttershadow.core;



import com.xfw.shuttershadow.event.ClientExitEvent;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xfw.shuttershadow.access.IEClientPlayNetworkHandler;
import com.xfw.shuttershadow.access.IEClientWorld;
import com.xfw.shuttershadow.access.IEMinecraftClient;
import com.xfw.shuttershadow.access.IEParticleManager;
import com.xfw.shuttershadow.access.IEWorldRenderer;
import com.xfw.shuttershadow.mixin.minecraft.client.IEClientLevelData;
import com.xfw.shuttershadow.mixin.minecraft.client.IEClientLevel_Accessor;
import com.xfw.shuttershadow.core.render.DimensionRenderHelper;
import com.xfw.shuttershadow.util.CountDownInt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 统一管理当前连接的各维度客户端世界、渲染器和光照资源。 */
@SuppressWarnings("resource")
// 仅供客户端使用。
public class ClientWorldLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientWorldLoader.class);
    
    private static final CountDownInt LOG_LIMIT = new CountDownInt(20);

    private static final Map<ResourceKey<Level>, ClientLevel> CLIENT_WORLD_MAP =
        new Object2ObjectOpenHashMap<>();
    public static final Map<ResourceKey<Level>, LevelRenderer> WORLD_RENDERER_MAP =
        new Object2ObjectOpenHashMap<>();
    public static final Map<ResourceKey<Level>, DimensionRenderHelper> RENDER_HELPER_MAP =
        new Object2ObjectOpenHashMap<>();
    
    public static @Nullable Map<ResourceKey<Level>, ResourceKey<DimensionType>> dimIdToDimTypeId;

    private static final Minecraft CLIENT = Minecraft.getInstance();
    
    private static boolean isInitialized = false;
    
    private static boolean isCreatingClientWorld = false;
    
    /** 注册退出世界时清理维度类型的回调。 */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientExitEvent.class, (e) -> dimIdToDimTypeId = null);
    }
    
    /** 返回主世界映射是否已登记。 */
    public static boolean getIsInitialized() {
        return isInitialized;
    }
    
    /** 判断是否正在创建远程世界，避免递归清理或重载。 */
    public static boolean getIsCreatingClientWorld() {
        return isCreatingClientWorld;
    }
    
    /** 更新其他维度的世界和渲染器，并更新各维度光照。 */
    public static void tick() {
        CLIENT_WORLD_MAP.values().forEach(world -> {
            if (CLIENT.level != world) {
                tickRemoteWorld(world);
            }
        });
        WORLD_RENDERER_MAP.values().forEach(worldRenderer -> {
            if (worldRenderer != CLIENT.levelRenderer) {
                worldRenderer.tick();
            }
        });
        
        boolean lightmapTextureConflict = false;
        for (DimensionRenderHelper helper : RENDER_HELPER_MAP.values()) {
            helper.tick();
            if (helper.world != CLIENT.level) {
                if (helper.lightmapTexture == CLIENT.gameRenderer.lightTexture()) {
                    assert CLIENT.level != null;
                    LOGGER.warn(
                        "Lightmap Texture Conflict {} {}",
                        helper.world.dimension().location(),
                        CLIENT.level.dimension().location()
                    );
                    lightmapTextureConflict = true;
                }
            }
        }
        if (lightmapTextureConflict) {
            disposeRenderHelpers();
        }
        
    }
    
    /** 逐个清理光照辅助并清映射。 */
    public static void disposeRenderHelpers() {
        RENDER_HELPER_MAP.values().forEach(DimensionRenderHelper::cleanUp);
        RENDER_HELPER_MAP.clear();
    }
    
    /** 在目标世界上下文中更新实体、世界和光照。 */
    private static void tickRemoteWorld(ClientLevel newWorld) {
        withSwitchedWorld(newWorld, () -> {
            try {
                newWorld.tickEntities();
                newWorld.tick(() -> true);
                
                newWorld.pollLightUpdates();
            }
            catch (Throwable e) {
                if (LOG_LIMIT.tryDecrement()) {
                    LOGGER.error("", e);
                }
            }
        });
    }
    
    /** 释放远程渲染资源，并清空各维度世界与初始化状态。 */
    public static void cleanUp() {
        WORLD_RENDERER_MAP.values().forEach(
            ClientWorldLoader::disposeWorldRenderer
        );
        
        for (ClientLevel clientWorld : CLIENT_WORLD_MAP.values()) {
            ((IEClientWorld) clientWorld).ip_resetWorldRendererRef();
        }
        
        CLIENT_WORLD_MAP.clear();
        WORLD_RENDERER_MAP.clear();
        
        disposeRenderHelpers();
        
        isInitialized = false;
    }
    
    /** 解绑世界并释放远程渲染器，保留当前主渲染器。 */
    private static void disposeWorldRenderer(LevelRenderer worldRenderer) {
        worldRenderer.setLevel(null);
        if (worldRenderer != CLIENT.levelRenderer) {
            worldRenderer.close();
            ((IEWorldRenderer) worldRenderer).portal_fullyDispose();
        }
    }
    
    /** 初始化后取得指定维度的渲染器。 */
    @NotNull
    public static LevelRenderer getWorldRenderer(ResourceKey<Level> dimension) {
        initializeIfNeeded();
        
        LevelRenderer result = WORLD_RENDERER_MAP.get(dimension);
        
        if (result == null) {
            LOGGER.warn(
                "Acquiring LevelRenderer before acquiring Level. Something is probably wrong. {}",
                dimension.location(), new Throwable()
            );
            
            // 世界渲染器随客户端世界一起创建，
            // 因此先确保客户端世界存在。
            getWorld(dimension);
            
            result = WORLD_RENDERER_MAP.get(dimension);
            
            if (result == null) {
                throw new RuntimeException("Unable to get LevelRenderer of " + dimension.location());
            }
        }
        
        return result;
    }
    
    
    /** 在客户端线程取得或创建指定维度的世界。 */
    @NotNull
    public static ClientLevel getWorld(ResourceKey<Level> dimension) {
        Validate.notNull(dimension, "dimension is null");
        Validate.isTrue(CLIENT.isSameThread());
        
        initializeIfNeeded();
        
        if (!CLIENT_WORLD_MAP.containsKey(dimension)) {
            return createSecondaryClientWorld(dimension);
        }
        
        ClientLevel result = CLIENT_WORLD_MAP.get(dimension);
        Validate.notNull(result, "null value in world map");
        return result;
    }
    
    /** 取得或创建服务器已公布的世界，非法维度返回空值。 */
    @Nullable
    public static ClientLevel getOptionalWorld(ResourceKey<Level> dimension) {
        Validate.notNull(dimension, "dimension is null");
        Validate.isTrue(CLIENT.isSameThread(), "not on client thread");
        
        if (getServerDimensions().contains(dimension)) {
            return getWorld(dimension);
        }
        
        return null;
    }
    
    /** 按维度懒创建光照辅助并验证世界维度一致。 */
    public static DimensionRenderHelper getDimensionRenderHelper(ResourceKey<Level> dimension) {
        initializeIfNeeded();
        
        DimensionRenderHelper result = RENDER_HELPER_MAP.computeIfAbsent(
            dimension, key -> new DimensionRenderHelper(getWorld(key))
        );
        
        Validate.isTrue(result.world.dimension() == dimension);
        
        return result;
    }
    
    /** 校验当前玩家世界，并登记主世界及其渲染资源。 */
    @SuppressWarnings("ConstantValue")
    public static void initializeIfNeeded() {
        if (!isInitialized) {
            Validate.isTrue(
                CLIENT.level != null, "level is null"
            );
            // 混入可能使当前世界渲染器为空。
            Validate.isTrue(
                CLIENT.levelRenderer != null, "levelRenderer is null"
            );
            
            Validate.notNull(
                CLIENT.player,
                "player is null. This may be caused by prior initialization failure. The log may provide useful information."
            );
            Validate.isTrue(
                CLIENT.player.level() == CLIENT.level,
                "The player level is not the same as client level"
            );
            
            ResourceKey<Level> playerDimension = CLIENT.level.dimension();
            CLIENT_WORLD_MAP.put(playerDimension, CLIENT.level);
            WORLD_RENDERER_MAP.put(playerDimension, CLIENT.levelRenderer);
            RENDER_HELPER_MAP.put(
                CLIENT.level.dimension(),
                new DimensionRenderHelper(CLIENT.level)
            );
            
            isInitialized = true;
        }
    }
    
    /** 创建目标维度的客户端世界和渲染器，完成资源加载后登记。 */
    @SuppressWarnings("DataFlowIssue")
    private static ClientLevel createSecondaryClientWorld(ResourceKey<Level> dimension) {
        Validate.notNull(CLIENT.player, "player is null");
        Validate.isTrue(CLIENT.isSameThread(), "not on client thread");
        
        Set<ResourceKey<Level>> dimIds = getServerDimensions();
        if (!dimIds.contains(dimension)) {
            throw new RuntimeException("Cannot create invalid client dimension " + dimension.location());
        }
        
        CLIENT.getProfiler().push("create_world");
        isCreatingClientWorld = true;
        
        int chunkLoadDistance = 3; // 自定义区块管理器不使用此距离。
        
        LevelRenderer worldRenderer = null;
        boolean registered = false;
        ClientLevel sourceWorld = CLIENT.level;
        Camera sourceEntityRenderCamera = CLIENT.getEntityRenderDispatcher().camera;
        
        ClientLevel newWorld;
        try {
            worldRenderer = new LevelRenderer(
                CLIENT,
                CLIENT.getEntityRenderDispatcher(),
                CLIENT.getBlockEntityRenderDispatcher(),
                CLIENT.renderBuffers()
            );
            ClientPacketListener mainNetHandler = CLIENT.player.connection;
            assert CLIENT.level != null;
            Map<MapId, MapItemSavedData> mapData = ((IEClientLevel_Accessor) CLIENT.level).ip_getMapData();

            Validate.notNull(
                dimIdToDimTypeId, "dimension type mapping is missing"
            );
            ResourceKey<DimensionType> dimensionTypeKey = dimIdToDimTypeId.get(dimension);
            
            if (dimensionTypeKey == null) {
                throw new IllegalStateException(
                    "Cannot find dimension type for %s in %s"
                        .formatted(dimension.location(), dimIdToDimTypeId)
                );
            }

            ClientLevel.ClientLevelData currentProperty =
                CLIENT.level.getLevelData();
            RegistryAccess registryManager = mainNetHandler.registryAccess();
            int simulationDistance = CLIENT.level.getServerSimulationDistance();
            
            Holder<DimensionType> dimensionType = registryManager
                .registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(dimensionTypeKey);
            
            // 每个世界使用独立的世界数据对象，
            // 不同世界的时间互不共享。
            ClientLevel.ClientLevelData properties = new ClientLevel.ClientLevelData(
                currentProperty.getDifficulty(),
                currentProperty.isHardcore(),
                ((IEClientLevelData) currentProperty).ip_getIsFlat()
            );
            newWorld = new ClientLevel(
                mainNetHandler,
                properties,
                dimension,
                dimensionType,
                chunkLoadDistance,
                simulationDistance,// 客户端世界当前不使用模拟距离。
                CLIENT::getProfiler,
                worldRenderer,
                CLIENT.level.isDebug(),
                CLIENT.level.getBiomeManager().biomeZoomSeed
            );
            
            // 所有世界共享同一份地图数据。
            ((IEClientLevel_Accessor) newWorld).ip_setMapData(mapData);

            // 所有世界共享同一个游戏刻速率管理器。
            ((IEClientWorld) newWorld).ip_setTickRateManager(CLIENT.level.tickRateManager());

            worldRenderer.setLevel(newWorld);

            worldRenderer.onResourceManagerReload(CLIENT.getResourceManager());

            CLIENT_WORLD_MAP.put(dimension, newWorld);
            WORLD_RENDERER_MAP.put(dimension, worldRenderer);
            registered = true;

        }
        catch (Throwable e) {
            // 未注册的渲染器不会进入退出清理；创建失败时立即释放它持有的资源。
            if (worldRenderer != null && !registered) {
                try {
                    disposeWorldRenderer(worldRenderer);
                }
                catch (Throwable cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                finally {
                    // setLevel(null) 同时清空共用实体渲染器，失败清理后恢复玩家世界。
                    CLIENT.getEntityRenderDispatcher().setLevel(sourceWorld);
                    CLIENT.getEntityRenderDispatcher().camera = sourceEntityRenderCamera;
                }
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                "Creating Client World " + dimension.location() + " " + CLIENT_WORLD_MAP.keySet(),
                e
            );
        }
        finally {
            isCreatingClientWorld = false;
            CLIENT.getProfiler().pop();
        }

        return newWorld;
    }
    
    /** 从主连接取得服务器公布的维度键集合。 */
    public static Set<ResourceKey<Level>> getServerDimensions() {
        assert CLIENT.player != null;
        return CLIENT.player.connection.levels();
    }
    
    /** 返回已初始化的所有客户端世界集合。 */
    public static Collection<ClientLevel> getClientWorlds() {
        Validate.isTrue(isInitialized);
        
        return CLIENT_WORLD_MAP.values();
    }
    
    private static boolean isReloadingOtherWorldRenderers = false;
    
    /** 在对应世界上下文中重载各远程维度的渲染器。 */
    @SuppressWarnings("Convert2MethodRef")
    public static void _onWorldRendererReloaded() {
        Validate.isTrue(CLIENT.isSameThread());
        if (isReloadingOtherWorldRenderers) {
            return;
        }
        if (ClientWorldLoader.getIsCreatingClientWorld()) {
            return;
        }
        
        isReloadingOtherWorldRenderers = true;
        try {
            List<ResourceKey<Level>> toReload = WORLD_RENDERER_MAP.keySet().stream()
                .filter(d -> d != CLIENT.level.dimension()).toList();

            for (ResourceKey<Level> dim : toReload) {
                ClientLevel world = CLIENT_WORLD_MAP.get(dim);
                Validate.notNull(world, "missing client world %s", dim.location());
                withSwitchedWorld(
                    world,
                    () -> {
                        // 此处不能改为方法引用，
                        // 因为世界渲染器字段会随世界切换而变化。
                        CLIENT.levelRenderer.allChanged();
                    }
                );
            }
        }
        finally {
            isReloadingOtherWorldRenderers = false;
        }
    }
    
    /** 临时切换世界执行操作，结束后恢复原有世界与渲染上下文。 */
    @SuppressWarnings({"ReassignedVariable", "DataFlowIssue"})
    public static <T> T withSwitchedWorld(ClientLevel newWorld, Supplier<T> supplier) {
        Validate.isTrue(CLIENT.isSameThread(), "not on client thread");
        Validate.isTrue(CLIENT.player != null, "player is null");
        
        ClientPacketListener networkHandler = CLIENT.getConnection();
        assert networkHandler != null;
        
        ClientLevel originalWorld = CLIENT.level;
        LevelRenderer originalWorldRenderer = CLIENT.levelRenderer;
        ClientLevel originalNetHandlerWorld = networkHandler.getLevel();
        
        LevelRenderer newWorldRenderer = getWorldRenderer(newWorld.dimension());
        
        Validate.notNull(newWorldRenderer, "new world renderer is null");
        
        CLIENT.level = newWorld;
        ((IEParticleManager) CLIENT.particleEngine).ip_setWorld(newWorld);
        ((IEMinecraftClient) CLIENT).ip_setWorldRenderer(newWorldRenderer);
        ((IEClientPlayNetworkHandler) networkHandler).ip_setWorld(newWorld);
        
        try {
            return supplier.get();
        }
        finally {
            if (CLIENT.level != newWorld) {
                LOGGER.error("Respawn packet should not be redirected");
                originalWorld = CLIENT.level;
                originalWorldRenderer = CLIENT.levelRenderer;
                // 混入允许重新赋值当前世界渲染器。
            }
            
            CLIENT.level = originalWorld;
            ((IEMinecraftClient) CLIENT).ip_setWorldRenderer(originalWorldRenderer);
            ((IEParticleManager) CLIENT.particleEngine).ip_setWorld(originalWorld);
            ((IEClientPlayNetworkHandler) networkHandler).ip_setWorld(originalNetHandlerWorld);
        }
    }
    
    /** 临时切换世界执行无返回值操作，并保证恢复上下文。 */
    public static void withSwitchedWorld(ClientLevel newWorld, Runnable runnable) {
        withSwitchedWorld(newWorld, () -> {
            runnable.run();
            return null;
        });
    }
    
    /** 在有效目标世界中执行操作，非法维度记录日志并跳过。 */
    public static void withSwitchedWorldFailSoft(ResourceKey<Level> dim, Runnable runnable) {
        ClientLevel world = getOptionalWorld(dim);
        
        if (world == null) {
            LOGGER.error(
                "Ignoring redirected task of invalid dimension {}", dim.location(), new Throwable()
            );
            return;
        }
        
        withSwitchedWorld(world, runnable);
    }
    
}

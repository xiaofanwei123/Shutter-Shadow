package com.xfw.shuttershadow.core;

// Shuttershadow phase seven: relocated into the camera core.

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

@SuppressWarnings("resource")
//@OnlyIn(Dist.CLIENT)
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
    
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientExitEvent.class, (e) -> dimIdToDimTypeId = null);
    }
    
    public static boolean getIsInitialized() {
        return isInitialized;
    }
    
    public static boolean getIsCreatingClientWorld() {
        return isCreatingClientWorld;
    }
    
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
                    LOGGER.info(
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
            LOGGER.info("Refreshed Lightmaps");
        }
        
    }
    
    public static void disposeRenderHelpers() {
        RENDER_HELPER_MAP.values().forEach(DimensionRenderHelper::cleanUp);
        RENDER_HELPER_MAP.clear();
    }
    
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
    
    private static void disposeWorldRenderer(LevelRenderer worldRenderer) {
        worldRenderer.setLevel(null);
        if (worldRenderer != CLIENT.levelRenderer) {
            worldRenderer.close();
            ((IEWorldRenderer) worldRenderer).portal_fullyDispose();
        }
    }
    
    @NotNull
    public static LevelRenderer getWorldRenderer(ResourceKey<Level> dimension) {
        initializeIfNeeded();
        
        LevelRenderer result = WORLD_RENDERER_MAP.get(dimension);
        
        if (result == null) {
            LOGGER.warn(
                "Acquiring LevelRenderer before acquiring Level. Something is probably wrong. {}",
                dimension.location(), new Throwable()
            );
            
            // the world renderer is created along with the world
            // so create the world now
            getWorld(dimension);
            
            result = WORLD_RENDERER_MAP.get(dimension);
            
            if (result == null) {
                throw new RuntimeException("Unable to get LevelRenderer of " + dimension.location());
            }
        }
        
        return result;
    }
    
    
    /**
     * Get the client world and create if missing.
     * If the dimension intId is invalid, it will throw an error
     */
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
    
    /**
     * Get the client world and create if missing.
     * If the dimension intId is invalid, it will return null
     */
    @Nullable
    public static ClientLevel getOptionalWorld(ResourceKey<Level> dimension) {
        Validate.notNull(dimension, "dimension is null");
        Validate.isTrue(CLIENT.isSameThread(), "not on client thread");
        
        if (getServerDimensions().contains(dimension)) {
            return getWorld(dimension);
        }
        
        return null;
    }
    
    public static DimensionRenderHelper getDimensionRenderHelper(ResourceKey<Level> dimension) {
        initializeIfNeeded();
        
        DimensionRenderHelper result = RENDER_HELPER_MAP.computeIfAbsent(
            dimension, key -> new DimensionRenderHelper(getWorld(key))
        );
        
        Validate.isTrue(result.world.dimension() == dimension);
        
        return result;
    }
    
    @SuppressWarnings("ConstantValue")
    public static void initializeIfNeeded() {
        if (!isInitialized) {
            Validate.isTrue(
                CLIENT.level != null, "level is null"
            );
            // note: client.levelRenderer is not necessarily not null due to mixin
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
        
        int chunkLoadDistance = 3; // my own chunk manager doesn't need it
        
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
            
            // currently use a separated level data object
            // day time is not shared between worlds
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
                simulationDistance,// seems that client world does not use this
                CLIENT::getProfiler,
                worldRenderer,
                CLIENT.level.isDebug(),
                CLIENT.level.getBiomeManager().biomeZoomSeed
            );
            
            // all worlds share the same map data map
            ((IEClientLevel_Accessor) newWorld).ip_setMapData(mapData);

            // all worlds share the same tick rate manager
            ((IEClientWorld) newWorld).ip_setTickRateManager(CLIENT.level.tickRateManager());

            worldRenderer.setLevel(newWorld);

            worldRenderer.onResourceManagerReload(CLIENT.getResourceManager());

            CLIENT_WORLD_MAP.put(dimension, newWorld);
            WORLD_RENDERER_MAP.put(dimension, worldRenderer);
            registered = true;

            LOGGER.info("Client World Created {}", dimension.location());
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
    
    public static Set<ResourceKey<Level>> getServerDimensions() {
        assert CLIENT.player != null;
        return CLIENT.player.connection.levels();
    }
    
    public static Collection<ClientLevel> getClientWorlds() {
        Validate.isTrue(isInitialized);
        
        return CLIENT_WORLD_MAP.values();
    }
    
    private static boolean isReloadingOtherWorldRenderers = false;
    
    @SuppressWarnings("Convert2MethodRef")
    public static void _onWorldRendererReloaded() {
        Validate.isTrue(CLIENT.isSameThread());
        if (CLIENT.level != null) {
            LOGGER.info("WorldRenderer reloaded {}", CLIENT.level.dimension().location());
        }
        
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
                        // cannot be replaced into method reference
                        // because levelRenderer field is actually mutable
                        CLIENT.levelRenderer.allChanged();
                    }
                );
            }
        }
        finally {
            isReloadingOtherWorldRenderers = false;
        }
    }
    
    /**
     * It will not switch the dimension of client player
     */
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
                // client.levelRenderer is not final by mixin.
            }
            
            CLIENT.level = originalWorld;
            ((IEMinecraftClient) CLIENT).ip_setWorldRenderer(originalWorldRenderer);
            ((IEParticleManager) CLIENT.particleEngine).ip_setWorld(originalWorld);
            ((IEClientPlayNetworkHandler) networkHandler).ip_setWorld(originalNetHandlerWorld);
        }
    }
    
    public static void withSwitchedWorld(ClientLevel newWorld, Runnable runnable) {
        withSwitchedWorld(newWorld, () -> {
            runnable.run();
            return null;
        });
    }
    
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

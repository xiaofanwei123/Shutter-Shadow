"""Execute production session and shutter route resolution against small camera worlds."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/xfw/shuttershadow"
PRODUCTION = (
    "network/RemoteCameraSession.java",
    "network/CameraSessionRequestC2S.java",
    "RemoteCaptureContext.java",
)


def declaration(path, signature):
    text = path.read_text(encoding="utf-8")
    start = text.index(signature)
    opening = text.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


FIXTURES = {
    "org.jetbrains.annotations.Nullable": "public @interface Nullable {}",
    "net.minecraft.resources.ResourceLocation": """public record ResourceLocation(String value) {
        public static ResourceLocation parse(String value) { return new ResourceLocation(value); }
        public static ResourceLocation fromNamespaceAndPath(String namespace, String path) { return parse(namespace + ":" + path); }
        public String toString() { return value; }
    }""",
    "net.minecraft.resources.ResourceKey": """public record ResourceKey<T>(ResourceLocation location) {
        public static <T> ResourceKey<T> create(Object registry, ResourceLocation location) { return new ResourceKey<>(location); }
    }""",
    "net.minecraft.core.RegistryAccess": "public class RegistryAccess {}",
    "net.minecraft.core.registries.Registries": "public class Registries { public static final Object DIMENSION = new Object(); }",
    "net.minecraft.core.registries.BuiltInRegistries": """public class BuiltInRegistries {
        public static final Items ITEM = new Items();
        public static class Items { public net.minecraft.resources.ResourceLocation getKey(net.minecraft.world.item.Item item) { return item.id; } }
    }""",
    "net.minecraft.core.BlockPos": """public record BlockPos(int x, int y, int z) {
        public static BlockPos containing(net.minecraft.world.phys.Vec3 position) { return new BlockPos((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z)); }
        public int getX() { return x; } public int getY() { return y; } public int getZ() { return z; }
    }""",
    "net.minecraft.world.phys.Vec3": """public class Vec3 {
        public static final Vec3 ZERO = new Vec3(0, 0, 0); public final double x, y, z;
        public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        public Vec3 subtract(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
        public Vec3 add(double x, double y, double z) { return new Vec3(this.x + x, this.y + y, this.z + z); }
        public Vec3 add(Vec3 other) { return add(other.x, other.y, other.z); }
        public double distanceToSqr(Vec3 other) { double dx=x-other.x, dy=y-other.y, dz=z-other.z; return dx*dx+dy*dy+dz*dz; }
        public boolean equals(Object other) { return other instanceof Vec3 vec && x == vec.x && y == vec.y && z == vec.z; }
        public int hashCode() { return java.util.Objects.hash(x, y, z); }
        public String toString() { return x + "," + y + "," + z; }
    }""",
    "net.minecraft.world.level.dimension.DimensionType": """public record DimensionType(double coordinateScale) {
        public static double getTeleportationScale(DimensionType source, DimensionType target) { return source.coordinateScale / target.coordinateScale; }
    }""",
    "net.minecraft.world.level.Level": """public class Level {
        protected net.minecraft.resources.ResourceKey<Level> dimension;
        protected final net.minecraft.world.level.dimension.DimensionType type;
        public final java.util.Map<Integer, net.minecraft.world.entity.Entity> entities = new java.util.HashMap<>();
        public Level(net.minecraft.resources.ResourceKey<Level> dimension, double scale) { this.dimension = dimension; type = new net.minecraft.world.level.dimension.DimensionType(scale); }
        public net.minecraft.resources.ResourceKey<Level> dimension() { return dimension; }
        public net.minecraft.world.level.dimension.DimensionType dimensionType() { return type; }
        public net.minecraft.world.entity.Entity getEntity(int id) { return entities.get(id); }
        public boolean hasChunkAt(net.minecraft.core.BlockPos pos) { return true; }
        public Fluid getFluidState(net.minecraft.core.BlockPos pos) { return new Fluid(); }
        public static class Fluid { public boolean is(Object tag) { return false; } public double getHeight(Level level, net.minecraft.core.BlockPos pos) { return 0; } }
    }""",
    "net.minecraft.tags.FluidTags": "public class FluidTags { public static final Object WATER = new Object(); }",
    "net.minecraft.server.level.ServerLevel": """public class ServerLevel extends net.minecraft.world.level.Level {
        private final net.minecraft.core.RegistryAccess registry = new net.minecraft.core.RegistryAccess(); public long ticks;
        public ServerLevel(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, double scale) { super(dimension, scale); }
        public net.minecraft.core.RegistryAccess registryAccess() { return registry; } public long getGameTime() { return ticks; }
        public java.util.List<ServerPlayer> players() { return entities.values().stream().filter(entity -> entity instanceof ServerPlayer).map(entity -> (ServerPlayer) entity).toList(); }
    }""",
    "net.minecraft.server.MinecraftServer": """public class MinecraftServer {
        public final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, net.minecraft.server.level.ServerLevel> levels = new java.util.HashMap<>();
        public final net.minecraft.server.players.PlayerList players = new net.minecraft.server.players.PlayerList();
        public net.minecraft.server.level.ServerLevel getLevel(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) { return levels.get(dimension); }
        public net.minecraft.server.players.PlayerList getPlayerList() { return players; }
        public net.minecraft.server.level.ServerLevel overworld() { return levels.values().iterator().next(); }
    }""",
    "net.minecraft.server.players.PlayerList": """public class PlayerList {
        public final java.util.Map<java.util.UUID, net.minecraft.server.level.ServerPlayer> players = new java.util.HashMap<>();
        public int viewDistance = 8;
        public net.minecraft.server.level.ServerPlayer getPlayer(java.util.UUID id) { return players.get(id); }
        public int getViewDistance() { return viewDistance; }
    }""",
    "net.minecraft.world.entity.Pose": "public enum Pose { STANDING }",
    "net.minecraft.world.entity.EntityDimensions": "public class EntityDimensions { public EntityDimensions withEyeHeight(float height) { return this; } }",
    "net.minecraft.world.entity.EntityType": "public class EntityType { public static final EntityType MARKER = new EntityType(); }",
    "net.minecraft.world.entity.Entity": """public class Entity {
        public final int id; public net.minecraft.world.level.Level world;
        private net.minecraft.world.phys.Vec3 position = net.minecraft.world.phys.Vec3.ZERO;
        public Entity(int id, net.minecraft.world.level.Level world) { this.id = id; this.world = world; }
        public int getId() { return id; } public net.minecraft.world.level.Level level() { return world; }
        public net.minecraft.world.phys.Vec3 position() { return position; }
        public net.minecraft.core.BlockPos blockPosition() { return net.minecraft.core.BlockPos.containing(position); }
        public void setPos(net.minecraft.world.phys.Vec3 pos) { position = pos; }
        public net.minecraft.world.phys.Vec3 getEyePosition() { return position.add(0, getEyeHeight(), 0); }
        public Pose getPose() { return Pose.STANDING; } public void setPose(Pose pose) {} public void refreshDimensions() {}
        public EntityDimensions getDimensions(Pose pose) { return new EntityDimensions(); }
        public float getEyeHeight() { return 1.6F; } public float getXRot() { return 0; } public float getYRot() { return 0; }
        public void setXRot(float angle) {} public void setYRot(float angle) {} public boolean isUnderWater() { return false; }
    }""",
    "net.minecraft.world.entity.Marker": "public class Marker extends Entity { public Marker(EntityType type, net.minecraft.world.level.Level world) { super(-1, world); } }",
    "net.minecraft.world.entity.player.Player": """public class Player extends net.minecraft.world.entity.Entity implements io.github.mortuusars.exposure.world.entity.CameraHolder {
        public Player(int id, net.minecraft.world.level.Level world) { super(id, world); }
        public net.minecraft.core.RegistryAccess registryAccess() { return new net.minecraft.core.RegistryAccess(); }
        public net.minecraft.world.entity.Entity asHolderEntity() { return this; }
        public java.util.Optional<Player> getPlayerExecutingExposure() { return java.util.Optional.of(this); }
        public java.util.Optional<net.minecraft.server.level.ServerPlayer> getServerPlayerExecutingExposure() {
            return this instanceof net.minecraft.server.level.ServerPlayer server ? java.util.Optional.of(server) : java.util.Optional.empty();
        }
    }""",
    "net.minecraft.server.level.ServerPlayer": """public class ServerPlayer extends net.minecraft.world.entity.player.Player {
        public final net.minecraft.server.MinecraftServer server; public final java.util.UUID uuid;
        public final Connection connection = new Connection();
        public static class Connection { public final Sender chunkSender = new Sender(); }
        public static class Sender { public final java.util.Set<Long> pending = new java.util.HashSet<>(); public boolean isPending(long pos) { return pending.contains(pos); } }
        public boolean alive = true, removed; public net.minecraft.world.item.ItemStack main = net.minecraft.world.item.ItemStack.EMPTY, off = net.minecraft.world.item.ItemStack.EMPTY;
        public ServerPlayer(int id, java.util.UUID uuid, net.minecraft.server.MinecraftServer server, ServerLevel world) { super(id, world); this.uuid = uuid; this.server = server; }
        public java.util.UUID getUUID() { return uuid; } public boolean isAlive() { return alive; } public boolean isRemoved() { return removed; }
        public net.minecraft.server.MinecraftServer getServer() { return server; } public ServerLevel serverLevel() { return (ServerLevel) world; }
        public net.minecraft.world.item.ItemStack getMainHandItem() { return main; } public net.minecraft.world.item.ItemStack getOffhandItem() { return off; }
    }""",
    "net.minecraft.world.item.Item": "public class Item { public final net.minecraft.resources.ResourceLocation id; public Item(net.minecraft.resources.ResourceLocation id) { this.id = id; } }",
    "net.minecraft.world.level.ChunkPos": "public class ChunkPos { public static long asLong(int x, int z) { return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32); } }",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {
        public static final ItemStack EMPTY = new ItemStack(null); private final Item item;
        public ItemStack filter = EMPTY; public net.minecraft.resources.ResourceLocation target; public boolean active;
        public ItemStack(Item item) { this.item = item; } public Item getItem() { return item; } public boolean isEmpty() { return item == null; }
        public ItemStack copy() { var result = new ItemStack(item); result.active = active; result.target = target; result.filter = filter; return result; }
    }""",
    "io.github.mortuusars.exposure.world.item.camera.CameraItem": """public class CameraItem extends net.minecraft.world.item.Item {
        public CameraItem(net.minecraft.resources.ResourceLocation id) { super(id); }
        public boolean isActive(net.minecraft.world.item.ItemStack stack) { return stack.active; }
        public io.github.mortuusars.exposure.util.PointOfView getPointOfView(io.github.mortuusars.exposure.world.entity.CameraHolder holder, net.minecraft.world.item.ItemStack stack) {
            return new io.github.mortuusars.exposure.util.PointOfView(holder.asHolderEntity().getEyePosition());
        }
        public double getViewfinderFov(net.minecraft.world.level.Level level, net.minecraft.world.item.ItemStack camera) { return 60; }
    }""",
    "io.github.mortuusars.exposure.util.PointOfView": "public record PointOfView(net.minecraft.world.phys.Vec3 pos) {}",
    "io.github.mortuusars.exposure.world.item.camera.Attachment": """public class Attachment {
        public static final Attachment FILTER = new Attachment();
        public Reading get(net.minecraft.world.item.ItemStack camera) { return new Reading(camera.filter); }
        public record Reading(net.minecraft.world.item.ItemStack filter) { public net.minecraft.world.item.ItemStack getForReading() { return filter; } }
    }""",
    "io.github.mortuusars.exposure.world.entity.CameraOperator": "public interface CameraOperator {}",
    "io.github.mortuusars.exposure.world.entity.CameraHolder": """public interface CameraHolder {
        net.minecraft.world.entity.Entity asHolderEntity();
        default net.minecraft.world.entity.Entity getExposureAuthorEntity() { return asHolderEntity(); }
        default java.util.Optional<net.minecraft.world.entity.player.Player> getPlayerExecutingExposure() { return java.util.Optional.empty(); }
        default java.util.Optional<net.minecraft.server.level.ServerPlayer> getServerPlayerExecutingExposure() { return java.util.Optional.empty(); }
        default java.util.Optional<net.minecraft.world.entity.player.Player> getPlayerAwardedForExposure() { return getPlayerExecutingExposure(); }
        default java.util.Optional<net.minecraft.server.level.ServerPlayer> getServerPlayerAwardedForExposure() { return getServerPlayerExecutingExposure(); }
        default java.util.Optional<CameraOperator> getExposureCameraOperator() { return java.util.Optional.empty(); }
    }""",
    "io.github.mortuusars.exposure.world.entity.CameraStandEntity": """public class CameraStandEntity extends net.minecraft.world.entity.Entity implements CameraHolder {
        public net.minecraft.world.item.ItemStack camera; public net.minecraft.server.level.ServerPlayer executing;
        public CameraStandEntity(int id, net.minecraft.server.level.ServerLevel world, net.minecraft.server.level.ServerPlayer executing, net.minecraft.world.item.ItemStack camera) { super(id, world); this.executing = executing; this.camera = camera; }
        public net.minecraft.world.item.ItemStack getCamera() { return camera; } public boolean isCameraActive() { return camera.active; }
        public net.minecraft.world.entity.Entity asHolderEntity() { return this; }
        public java.util.Optional<net.minecraft.server.level.ServerPlayer> getServerPlayerExecutingExposure() { return java.util.Optional.ofNullable(executing); }
    }""",
    "net.neoforged.bus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
    "net.neoforged.fml.common.EventBusSubscriber": "public @interface EventBusSubscriber { String modid(); }",
    "net.neoforged.neoforge.event.entity.player.PlayerEvent": "public class PlayerEvent { public static class PlayerLoggedOutEvent { public Object getEntity() { return null; } } }",
    "net.neoforged.neoforge.event.server.ServerStoppingEvent": "public class ServerStoppingEvent {}",
    "net.neoforged.neoforge.event.tick.ServerTickEvent": "public class ServerTickEvent { public static class Post {} }",
    "net.neoforged.neoforge.network.PacketDistributor": """public class PacketDistributor {
        public static final java.util.List<Object> scenes = new java.util.ArrayList<>();
        public static final java.util.List<com.xfw.shuttershadow.network.CameraSessionRequestC2S> requests = new java.util.ArrayList<>();
        public static void sendToPlayer(Object player, Object scene) { scenes.add(scene); }
        public static void sendToServer(com.xfw.shuttershadow.network.CameraSessionRequestC2S request) { requests.add(request); }
    }""",
    "net.minecraft.network.FriendlyByteBuf": """public class FriendlyByteBuf {
        private final java.util.ArrayDeque<Object> values = new java.util.ArrayDeque<>();
        public void writeVarLong(long value) { values.add(value); } public long readVarLong() { return (Long) values.remove(); }
        public void writeVarInt(int value) { values.add(value); } public int readVarInt() { return (Integer) values.remove(); }
        public void writeResourceLocation(net.minecraft.resources.ResourceLocation value) { values.add(value); }
        public net.minecraft.resources.ResourceLocation readResourceLocation() { return (net.minecraft.resources.ResourceLocation) values.remove(); }
    }""",
    "net.minecraft.network.codec.StreamCodec": """public interface StreamCodec<B, T> {
        void encode(B buf, T value); T decode(B buf);
        static <B, T> StreamCodec<B, T> of(java.util.function.BiConsumer<B, T> writer, java.util.function.Function<B, T> reader) {
            return new StreamCodec<>() { public void encode(B buf, T value) { writer.accept(buf, value); } public T decode(B buf) { return reader.apply(buf); } };
        }
    }""",
    "net.minecraft.network.protocol.common.custom.CustomPacketPayload": """public interface CustomPacketPayload {
        record Type<T extends CustomPacketPayload>(net.minecraft.resources.ResourceLocation id) {}
        Type<? extends CustomPacketPayload> type();
    }""",
    "com.xfw.shuttershadow.Shuttershadow": "public class Shuttershadow { public static final String MODID = \"shuttershadow\"; }",
    "com.xfw.shuttershadow.ShuttershadowConfig": """public class ShuttershadowConfig {
        public static int maxViewDistance = 8;
        public static int standPlayerRadius() { return 128; }
        public static int maxRemoteViewDistance() { return maxViewDistance; }
    }""",
    "com.xfw.shuttershadow.ExposureVisibility": """public class ExposureVisibility {
        public static java.util.List<net.minecraft.world.entity.player.Player> playersInFrame(io.github.mortuusars.exposure.world.entity.CameraHolder holder, net.minecraft.world.item.ItemStack camera) { return java.util.List.of(); }
        public static boolean isVisible(io.github.mortuusars.exposure.util.PointOfView view, net.minecraft.world.entity.Entity entity, double fov) { return true; }
    }""",
    "com.xfw.shuttershadow.network.RemoteSceneStopS2C": "public class RemoteSceneStopS2C {}",
    "com.xfw.shuttershadow.network.RemoteSceneStartS2C": """public record RemoteSceneStartS2C(long sequence, net.minecraft.resources.ResourceLocation dimension,
        net.minecraft.world.phys.Vec3 position, net.minecraft.world.phys.Vec3 sourceOrigin, double coordinateScale, int maxRenderDistance,
        net.minecraft.resources.ResourceLocation sourceDimension, java.util.List<java.util.UUID> projectedPlayers) {}""",
    "com.xfw.shuttershadow.network.RemoteStandPreparation": """public class RemoteStandPreparation {
        public static boolean ownsCapture(long seq) { return false; }
        public static boolean isRedstoneCapture(io.github.mortuusars.exposure.world.entity.CameraStandEntity stand) { return false; }
        public static com.xfw.shuttershadow.RemoteCaptureContext preparedContext(io.github.mortuusars.exposure.world.entity.CameraHolder holder, net.minecraft.world.item.ItemStack camera) { return null; }
    }""",
    "com.xfw.shuttershadow.util.McHelper": """public class McHelper {
        public static int loadDistance = 8;
        public static int getPlayerLoadDistance(net.minecraft.server.level.ServerPlayer player) { return loadDistance; }
    }""",
    "com.xfw.shuttershadow.api.ChunkLoader": """public record ChunkLoader(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int x, int z, int radius) {
        public static int fullyLoadedQueries;
        public boolean isFullyLoaded(net.minecraft.server.MinecraftServer server) { fullyLoadedQueries++; return true; }
    }""",
    "com.xfw.shuttershadow.api.ChunkLoading": """public class ChunkLoading {
        public static int adds, removes;
        public static final java.util.List<Object> loaders = new java.util.ArrayList<>();
        public static void addChunkLoaderForPlayer(Object player, Object loader) { adds++; loaders.add(loader); }
        public static void removeChunkLoaderForPlayer(Object player, Object loader) { removes++; loaders.removeIf(value -> value == loader); }
    }""",
    "com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking": """public class RemoteChunkTracking {
        public static class Record { public boolean isLoadedToPlayer = true; }
        public static final java.util.Set<String> missing = new java.util.HashSet<>(), nativeChunks = new java.util.HashSet<>();
        public static String key(Object dimension, int x, int z) { return dimension + ":" + x + ":" + z; }
        public static boolean isPlayerWatchingChunk(Object player, Object dimension, int x, int z) { return !missing.contains(key(dimension, x, z)); }
        public static boolean isNativeChunkTracked(Object player, Object dimension, int x, int z) { return nativeChunks.contains(key(dimension, x, z)); }
        public static boolean isPlayerWatchingChunk(Object player, Object dimension, int x, int z, java.util.function.Predicate<Record> predicate) { return true; }
        public static void immediatelyUpdateForPlayer(Object player) {}
    }""",
    "net.minecraft.client.multiplayer.ClientLevel": """public class ClientLevel extends net.minecraft.world.level.Level {
        public ClientLevel(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) { super(dimension, 1); }
    }""",
    "net.minecraft.client.multiplayer.ClientPacketListener": "public class ClientPacketListener {}",
    "net.minecraft.client.Minecraft": """public class Minecraft {
        private static final Minecraft INSTANCE = new Minecraft(); public net.minecraft.world.entity.player.Player player;
        public net.minecraft.client.multiplayer.ClientPacketListener connection;
        public static Minecraft getInstance() { return INSTANCE; }
        public net.minecraft.client.multiplayer.ClientPacketListener getConnection() { return connection; }
        public net.minecraft.world.entity.Entity getCameraEntity() { return player; }
    }""",
    "net.neoforged.neoforge.client.event.ClientTickEvent": "public class ClientTickEvent { public static class Post {} }",
    "io.github.mortuusars.exposure.world.camera.Camera": """public class Camera {
        private final net.minecraft.world.item.ItemStack stack;
        public Camera(net.minecraft.world.item.ItemStack stack) { this.stack = stack; }
        public net.minecraft.world.item.ItemStack getItemStack() { return stack; }
    }""",
    "io.github.mortuusars.exposure.world.camera.CameraOnStand": """public class CameraOnStand extends Camera {
        private final io.github.mortuusars.exposure.world.entity.CameraStandEntity stand;
        public CameraOnStand(io.github.mortuusars.exposure.world.entity.CameraStandEntity stand) { super(stand.getCamera()); this.stand = stand; }
        public io.github.mortuusars.exposure.world.entity.CameraStandEntity getStand() { return stand; }
    }""",
    "io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder": """public class Viewfinder {
        private final io.github.mortuusars.exposure.world.camera.Camera camera;
        public Viewfinder(net.minecraft.world.item.ItemStack camera) { this.camera = new io.github.mortuusars.exposure.world.camera.Camera(camera); }
        public io.github.mortuusars.exposure.world.camera.Camera camera() { return camera; }
    }""",
    "io.github.mortuusars.exposure.client.camera.CameraClient": """public class CameraClient {
        public static boolean active; public static io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder viewfinder;
        public static boolean isActive() { return active; }
        public static io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder viewfinder() { return viewfinder; }
        public static void resetCameraEntity() {}
    }""",
    "com.xfw.shuttershadow.client.SourceStandCapture": "public class SourceStandCapture { public static boolean isRenderingSourceScene() { return false; } }",
}


def fixtures():
    result = dict(FIXTURES)
    api = JAVA / "api/DimensionFilters.java"
    result["com.xfw.shuttershadow.api.DimensionFilters"] = """
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.world.level.Level;
        import net.minecraft.world.phys.Vec3;
        import net.minecraft.world.level.dimension.DimensionType;
        import org.jetbrains.annotations.Nullable;
        import java.util.Objects;
        public class DimensionFilters {
            public record Key(ResourceLocation target, ResourceLocation source) {}
            public static final java.util.Map<Key, Route> routes = new java.util.HashMap<>();
            public static Route resolve(net.minecraft.core.RegistryAccess registry, net.minecraft.world.item.ItemStack filter, ResourceLocation source) {
                return filter == null || filter.isEmpty() ? null : routes.get(new Key(filter.target, source));
            }
    """ + "\n".join(declaration(api, method) for method in (
        "public record Route", "public static double horizontalScale", "public static Vec3 mapAbsolute",
        "public static Vec3 mapRelative")) + "}"
    client = JAVA / "client/ImmersiveCameraClient.java"
    result["com.xfw.shuttershadow.client.ImmersiveCameraClient"] = """
        import com.xfw.shuttershadow.api.DimensionFilters;
        import com.xfw.shuttershadow.network.CameraSessionRequestC2S;
        import com.xfw.shuttershadow.network.RemoteSceneStartS2C;
        import io.github.mortuusars.exposure.client.camera.CameraClient;
        import io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder;
        import io.github.mortuusars.exposure.world.camera.CameraOnStand;
        import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
        import io.github.mortuusars.exposure.world.item.camera.Attachment;
        import net.minecraft.client.Minecraft;
        import net.minecraft.client.multiplayer.ClientLevel;
        import net.minecraft.client.multiplayer.ClientPacketListener;
        import net.minecraft.world.item.ItemStack;
        import net.neoforged.neoforge.client.event.ClientTickEvent;
        import net.neoforged.neoforge.network.PacketDistributor;
        import java.util.Objects;
        public final class ImmersiveCameraClient {
            private static Session session;
            private static RemoteSceneStartS2C scene;
            private static long nextSequence;
            private static int requestRetryTicks, deferredCameraResetTicks;
            private static boolean suppressRemoteScene;
            private static void close() { session = null; scene = null; }
            private static void clearDetachedStandViewfinder() {}
            private static void tickRemoteParticles(Minecraft mc) {}
    """ + "\n".join(declaration(client, method) for method in (
        "private record Session", "public static void start", "public static void onTick",
        "private static DimensionFilters.Route mappingFor", "private static int cameraStandId")) + "}"
    return result


class CameraRouteIdentityTests(unittest.TestCase):
    def test_production_route_identity(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertIsNotNone(javac, "JDK 21 javac is required")
        self.assertIsNotNone(java, "JDK 21 java is required")
        with tempfile.TemporaryDirectory(prefix="shuttershadow-camera-route-") as temporary:
            folder = Path(temporary)
            sources = []
            for name, body in fixtures().items():
                path = folder / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf-8")
                sources.append(str(path))
            sources.extend(str(JAVA / path) for path in PRODUCTION)
            sources.append(str(ROOT / "tools/tests/CameraRouteIdentityTest.java"))
            compiled = subprocess.run([javac, "-encoding", "UTF-8", "--release", "21", "-d", str(folder), *sources],
                                      capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            for scenario in ("shared-filter-target", "scale-change", "valid-session", "multiple-stands",
                             "snapshot-route", "owner-replaced", "invalid-route", "expected-hand-target",
                             "delayed-components", "request-codec", "client-start", "client-retry", "capture-current-window",
                             "view-limit-default", "view-limit-handheld", "view-limit-stand", "view-limit-client-server",
                             "view-limit-live", "view-limit-capture"):
                with self.subTest(scenario=scenario):
                    executed = subprocess.run([java, "-ea", "-cp", str(folder), "CameraRouteIdentityTest", scenario],
                                              capture_output=True, text=True, encoding="utf-8", errors="replace")
                    self.assertEqual(executed.returncode, 0, executed.stdout + executed.stderr)
                    print(executed.stdout.strip(), flush=True)
            baseline = ROOT / "tools/fixtures/camera-route-before.zip"
            self.assertTrue(baseline.exists(), "Regression evidence needs the saved pre-fix source")
            with zipfile.ZipFile(baseline) as archive:
                original = archive.read("src/main/java/com/xfw/shuttershadow/client/ImmersiveCameraClient.java").decode("utf-8")
            baseline_source = folder / "CameraRouteBefore.java"
            baseline_source.write_text(original, encoding="utf-8")
            baseline_start = declaration(baseline_source, "public static void start")
            client_fixture = folder / "com/xfw/shuttershadow/client/ImmersiveCameraClient.java"
            patched_fixture = client_fixture.read_text(encoding="utf-8")
            patched_start = declaration(client_fixture, "public static void start")
            client_fixture.write_text(patched_fixture.replace(patched_start, baseline_start), encoding="utf-8")
            compiled = subprocess.run([javac, "-encoding", "UTF-8", "--release", "21", "-d", str(folder), *sources],
                                      capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            executed = subprocess.run([java, "-ea", "-cp", str(folder), "CameraRouteIdentityTest", "client-start"],
                                      capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertNotEqual(executed.returncode, 0, "Pre-fix start unexpectedly rejected the wrong dimension")
            self.assertIn("same sequence response with wrong target is rejected", executed.stderr)
            print("BASELINE reproduced: old client start accepted same-sequence Nether scene for End request", flush=True)


if __name__ == "__main__":
    unittest.main()

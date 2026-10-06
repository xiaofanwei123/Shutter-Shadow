"""Execute the production stand-transfer and viewfinder-cleanup methods without launching Minecraft."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from test_camera_route_identity import FIXTURES, declaration


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/xfw/shuttershadow"
CLIENT = JAVA / "client/ImmersiveCameraClient.java"
SERVER = JAVA / "DimensionFilmCapture.java"
BASELINE = ROOT / "tools/fixtures/stand-controller-before.zip"


def fixtures():
    names = (
        "net.minecraft.resources.ResourceLocation", "net.minecraft.resources.ResourceKey",
        "net.minecraft.core.RegistryAccess", "net.minecraft.core.registries.Registries",
        "net.minecraft.core.BlockPos", "net.minecraft.world.phys.Vec3",
        "net.minecraft.world.level.dimension.DimensionType", "net.minecraft.world.level.Level",
        "net.minecraft.server.level.ServerLevel", "net.minecraft.server.MinecraftServer",
        "net.minecraft.server.players.PlayerList", "net.minecraft.world.entity.Pose",
        "net.minecraft.world.entity.EntityDimensions", "net.minecraft.world.entity.EntityType",
        "net.minecraft.world.entity.Entity", "net.minecraft.world.item.Item",
        "net.minecraft.world.item.ItemStack", "net.minecraft.client.multiplayer.ClientLevel",
        "net.minecraft.client.multiplayer.ClientPacketListener",
        "net.neoforged.neoforge.client.event.ClientTickEvent",
    )
    result = {name: FIXTURES[name] for name in names}
    result.update({
        "com.xfw.shuttershadow.test.Events": """public final class Events {
            public static final java.util.List<String> events = new java.util.ArrayList<>();
            public static void add(String event) { events.add(event); }
        }""",
        "io.github.mortuusars.exposure.world.entity.CameraOperator": """public interface CameraOperator {
            io.github.mortuusars.exposure.world.camera.Camera getActiveExposureCamera();
            void setActiveExposureCamera(io.github.mortuusars.exposure.world.camera.Camera camera);
            void removeActiveExposureCamera();
            default java.util.Optional<io.github.mortuusars.exposure.world.camera.Camera> getActiveExposureCameraOptional() {
                var camera = getActiveExposureCamera();
                return camera == null || !camera.getItemStack().active ? java.util.Optional.empty() : java.util.Optional.of(camera);
            }
            net.minecraft.world.entity.Entity asOperatorEntity();
        }""",
        "io.github.mortuusars.exposure.world.entity.CameraHolder": """public interface CameraHolder {
            net.minecraft.world.entity.Entity asHolderEntity();
        }""",
        "net.minecraft.world.entity.player.Player": """public class Player extends net.minecraft.world.entity.Entity implements
                io.github.mortuusars.exposure.world.entity.CameraOperator, io.github.mortuusars.exposure.world.entity.CameraHolder {
            public io.github.mortuusars.exposure.world.camera.Camera activeCamera;
            public int removes;
            public Player(int id, net.minecraft.world.level.Level world) { super(id, world); }
            public net.minecraft.core.RegistryAccess registryAccess() { return new net.minecraft.core.RegistryAccess(); }
            public net.minecraft.world.entity.Entity asHolderEntity() { return this; }
            public net.minecraft.world.entity.Entity asOperatorEntity() { return this; }
            public io.github.mortuusars.exposure.world.camera.Camera getActiveExposureCamera() {
                return activeCamera == null || !activeCamera.getItemStack().active ? null : activeCamera;
            }
            public void setActiveExposureCamera(io.github.mortuusars.exposure.world.camera.Camera camera) { activeCamera = camera; }
            public void removeActiveExposureCamera() {
                removes++; activeCamera = null; com.xfw.shuttershadow.test.Events.add("remove:" + getId());
                if (net.minecraft.client.Minecraft.getInstance().player == this) {
                    io.github.mortuusars.exposure.client.camera.CameraClient.removeViewfinder();
                }
            }
        }""",
        "net.minecraft.server.level.ServerPlayer": """public class ServerPlayer extends net.minecraft.world.entity.player.Player {
            public final net.minecraft.server.MinecraftServer server; public final java.util.UUID uuid;
            public boolean alive = true, removed, accept = true;
            public ServerPlayer(int id, java.util.UUID uuid, net.minecraft.server.MinecraftServer server, ServerLevel world) {
                super(id, world); this.uuid = uuid; this.server = server;
            }
            public java.util.UUID getUUID() { return uuid; }
            public boolean isAlive() { return alive; } public boolean isRemoved() { return removed; }
            public net.minecraft.server.MinecraftServer getServer() { return server; }
        }""",
        "net.minecraft.client.Minecraft": """public class Minecraft {
            private static final Minecraft INSTANCE = new Minecraft();
            public net.minecraft.world.entity.player.Player player;
            public net.minecraft.client.multiplayer.ClientPacketListener connection;
            public net.minecraft.world.entity.Entity camera;
            public static Minecraft getInstance() { return INSTANCE; }
            public net.minecraft.client.multiplayer.ClientPacketListener getConnection() { return connection; }
            public net.minecraft.world.entity.Entity getCameraEntity() { return camera; }
        }""",
        "io.github.mortuusars.exposure.world.entity.CameraStandEntity": """public class CameraStandEntity extends
                net.minecraft.world.entity.Entity implements CameraHolder {
            public net.minecraft.world.item.ItemStack camera;
            public net.minecraft.world.entity.player.Player controller;
            public int stops;
            public CameraStandEntity(int id, net.minecraft.world.level.Level world, net.minecraft.world.item.ItemStack camera) {
                super(id, world); this.camera = camera;
            }
            public net.minecraft.world.item.ItemStack getCamera() { return camera; }
            public net.minecraft.world.entity.Entity asHolderEntity() { return this; }
            public void stopControlling() {
                stops++; com.xfw.shuttershadow.test.Events.add("stop:" + getId());
                if (controller != null && controller.level() == level()) {
                    com.xfw.shuttershadow.test.Events.add("stop-packet:" + controller.getId());
                }
                controller = null; camera.active = false;
            }
        }""",
        "io.github.mortuusars.exposure.world.camera.Camera": """public class Camera {
            private final net.minecraft.world.item.ItemStack stack;
            public Camera(net.minecraft.world.item.ItemStack stack) { this.stack = stack; }
            public net.minecraft.world.item.ItemStack getItemStack() { return stack; }
        }""",
        "io.github.mortuusars.exposure.world.camera.CameraOnStand": """public class CameraOnStand extends Camera {
            private final io.github.mortuusars.exposure.world.entity.CameraStandEntity stand;
            private final io.github.mortuusars.exposure.world.entity.CameraOperator operator;
            public CameraOnStand(io.github.mortuusars.exposure.world.entity.CameraOperator operator,
                    io.github.mortuusars.exposure.world.entity.CameraStandEntity stand) {
                super(stand.getCamera()); this.operator = operator; this.stand = stand;
            }
            public io.github.mortuusars.exposure.world.entity.CameraStandEntity getStand() { return stand; }
            public io.github.mortuusars.exposure.world.entity.CameraOperator getOperator() { return operator; }
        }""",
        "io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder": """public class Viewfinder {
            private final io.github.mortuusars.exposure.world.camera.Camera camera;
            public boolean open = true;
            public Viewfinder(io.github.mortuusars.exposure.world.camera.Camera camera) { this.camera = camera; }
            public io.github.mortuusars.exposure.world.camera.Camera camera() { return camera; }
            public void close() { open = false; io.github.mortuusars.exposure.client.camera.CameraClient.controlsOpen = false; }
        }""",
        "io.github.mortuusars.exposure.client.camera.CameraClient": """public class CameraClient {
            public static io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder viewfinder;
            public static boolean controlsOpen;
            public static int resets, deactivatePackets;
            public static boolean isActive() {
                var player = net.minecraft.client.Minecraft.getInstance().player;
                return player != null && player.getActiveExposureCameraOptional().isPresent();
            }
            public static io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder viewfinder() { return viewfinder; }
            public static void resetCameraEntity() {
                resets++; var mc = net.minecraft.client.Minecraft.getInstance(); mc.camera = mc.player;
            }
            public static void removeViewfinder() { if (viewfinder != null) viewfinder.close(); viewfinder = null; }
            public static void deactivate() { deactivatePackets++; }
        }""",
        "com.xfw.shuttershadow.client.SourceStandCapture": """public class SourceStandCapture {
            public static boolean rendering;
            public static boolean isRenderingSourceScene() { return rendering; }
        }""",
        "com.xfw.shuttershadow.network.CameraSessionRequestC2S": """public record CameraSessionRequestC2S(long sequence,
            net.minecraft.resources.ResourceLocation filter, net.minecraft.resources.ResourceLocation targetDimension, int cameraStandId) {}""",
        "com.xfw.shuttershadow.network.RemoteSceneStartS2C": FIXTURES["com.xfw.shuttershadow.network.RemoteSceneStartS2C"],
        "net.neoforged.neoforge.network.PacketDistributor": """public class PacketDistributor {
            public static final java.util.List<Object> requests = new java.util.ArrayList<>();
            public static void sendToServer(Object message) { requests.add(message); }
        }""",
        "com.xfw.shuttershadow.api.DimensionFilters": """public class DimensionFilters {
            public record Route(net.minecraft.resources.ResourceLocation filter, net.minecraft.resources.ResourceLocation dimension) {}
        }""",
        "com.xfw.shuttershadow.RemoteCaptureContext": """public record RemoteCaptureContext(
            io.github.mortuusars.exposure.world.entity.CameraHolder source, net.minecraft.server.level.ServerLevel level) {
            public net.minecraft.world.phys.Vec3 targetPosition(net.minecraft.server.level.ServerPlayer player) { return player.position(); }
        }""",
        "com.xfw.shuttershadow.network.RemoteCameraSession": """public class RemoteCameraSession {
            public static void closeBeforeDimensionTeleport(net.minecraft.server.level.ServerPlayer player) {
                com.xfw.shuttershadow.test.Events.add("close:" + player.getId());
            }
            public static void finishDimensionTeleport(net.minecraft.server.level.ServerPlayer player) {
                com.xfw.shuttershadow.test.Events.add("finish:" + player.getId());
            }
        }""",
    })
    result["com.xfw.shuttershadow.client.ImmersiveCameraClient"] = client_body()
    result["com.xfw.shuttershadow.DimensionFilmCapture"] = server_body()
    return result


def client_body():
    source = CLIENT.read_text(encoding="utf-8")
    fields = "\n".join(re.findall(r"private static [^;\n]+;", source.split("private record Session", 1)[0]))
    methods = (
        "private record Session", "public static void stopRemoteScene()",
        "public static void deferExposureStandCameraReset", "public static void onTick",
        "private static void clearDetachedStandViewfinder", "private static int cameraStandId",
    )
    return """
        import com.xfw.shuttershadow.api.DimensionFilters;
        import com.xfw.shuttershadow.network.CameraSessionRequestC2S;
        import com.xfw.shuttershadow.network.RemoteSceneStartS2C;
        import io.github.mortuusars.exposure.client.camera.CameraClient;
        import io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder;
        import io.github.mortuusars.exposure.world.camera.CameraOnStand;
        import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
        import io.github.mortuusars.exposure.world.entity.CameraOperator;
        import net.minecraft.client.Minecraft;
        import net.minecraft.client.multiplayer.ClientLevel;
        import net.minecraft.client.multiplayer.ClientPacketListener;
        import net.minecraft.world.entity.Entity;
        import net.neoforged.neoforge.client.event.ClientTickEvent;
        import net.neoforged.neoforge.network.PacketDistributor;
        import java.util.Objects;
        public final class ImmersiveCameraClient {
            private record CaptureSnapshot() {}
            private static void close() { session = null; scene = null; requestRetryTicks = 0; }
            private static DimensionFilters.Route mappingFor(Viewfinder finder) { return null; }
            private static void tickRemoteParticles(Minecraft mc) {}
    """ + fields + "\n" + "\n".join(declaration(CLIENT, method) for method in methods) + "\n}"


def server_body():
    return """
        import com.xfw.shuttershadow.network.RemoteCameraSession;
        import io.github.mortuusars.exposure.world.camera.CameraOnStand;
        import io.github.mortuusars.exposure.world.entity.CameraOperator;
        import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
        import net.minecraft.server.level.ServerPlayer;
        import net.minecraft.resources.ResourceKey;
        import net.minecraft.world.level.Level;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.phys.Vec3;
        import java.util.List;
        public final class DimensionFilmCapture {
            public static boolean playerFilm = true;
            private static boolean hasPlayerDimensionFilm(ItemStack camera) { return playerFilm; }
            private static boolean acceptsStandTeleport(ServerPlayer player) { return player.accept; }
            private static void teleport(ServerPlayer player, ResourceKey<Level> dimension, Vec3 position) {
                com.xfw.shuttershadow.test.Events.add("teleport:" + player.getId());
                player.world = player.getServer().getLevel(dimension); player.setPos(position);
            }
    """ + declaration(SERVER, "public static void teleportStandPlayersAfterPhoto(RemoteCaptureContext") + "\n}"


class StandControllerCleanupTests(unittest.TestCase):
    def test_production_cleanup(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertIsNotNone(javac, "JDK 21 javac is required")
        self.assertIsNotNone(java, "JDK 21 java is required")
        with tempfile.TemporaryDirectory(prefix="shuttershadow-stand-cleanup-") as temporary:
            folder = Path(temporary)
            sources = []
            for name, body in fixtures().items():
                path = folder / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf-8")
                sources.append(str(path))
            sources.append(str(ROOT / "tools/tests/StandControllerCleanupTest.java"))

            def compile_sources():
                compiled = subprocess.run([javac, "-encoding", "UTF-8", "--release", "21", "-d", str(folder), *sources],
                                          capture_output=True, text=True, encoding="utf-8", errors="replace")
                self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)

            def execute(scenario):
                return subprocess.run([java, "-ea", "-cp", str(folder), "StandControllerCleanupTest", scenario],
                                      capture_output=True, text=True, encoding="utf-8", errors="replace")

            compile_sources()
            for scenario in ("client-transfer", "client-inactive", "client-reset-entity", "client-valid-stand",
                             "client-handheld", "client-new-stand", "client-same-stand-reopen",
                             "client-new-stand-after-transfer", "client-render-camera-replaced",
                             "client-source-screenshot", "client-transfer-deferral", "server-operator",
                             "server-other-player", "server-other-stand", "server-other-operator",
                             "server-inactive", "server-rejected", "server-redstone"):
                with self.subTest(scenario=scenario):
                    executed = execute(scenario)
                    self.assertEqual(executed.returncode, 0, executed.stdout + executed.stderr)
                    print(executed.stdout.strip(), flush=True)

            self.assertTrue(BASELINE.exists(), "Regression evidence needs the saved pre-fix source")
            with zipfile.ZipFile(BASELINE) as archive:
                originals = {name: archive.read("src/main/java/com/xfw/shuttershadow/" + name).decode("utf-8")
                             for name in ("client/ImmersiveCameraClient.java", "DimensionFilmCapture.java")}
            for name, signature, scenario, failure in (
                    ("client/ImmersiveCameraClient.java", "private static void clearDetachedStandViewfinder",
                     "client-transfer", "transported operator viewfinder is removed"),
                    ("DimensionFilmCapture.java", "public static void teleportStandPlayersAfterPhoto(RemoteCaptureContext",
                     "server-operator", "operator is stopped before crossing dimensions")):
                target = folder / "com/xfw/shuttershadow" / name
                current = target.read_text(encoding="utf-8")
                before = folder / "Before.java"
                before.write_text(originals[name], encoding="utf-8")
                target.write_text(current.replace(declaration(target, signature), declaration(before, signature)), encoding="utf-8")
                compile_sources()
                executed = execute(scenario)
                self.assertNotEqual(executed.returncode, 0, "Pre-fix cleanup unexpectedly passed")
                self.assertIn(failure, executed.stderr)
                print("BASELINE reproduced: " + failure, flush=True)
                target.write_text(current, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()

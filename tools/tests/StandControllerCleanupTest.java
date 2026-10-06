import com.xfw.shuttershadow.DimensionFilmCapture;
import com.xfw.shuttershadow.RemoteCaptureContext;
import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import com.xfw.shuttershadow.client.SourceStandCapture;
import com.xfw.shuttershadow.test.Events;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder;
import io.github.mortuusars.exposure.world.camera.Camera;
import io.github.mortuusars.exposure.world.camera.CameraOnStand;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;
import java.util.UUID;

/** Exposure viewfinder/input ownership must end when the operator leaves its stand's dimension. */
public final class StandControllerCleanupTest {
    private static int checks;

    private static final class ClientEnvironment {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel source = new ClientLevel(key("minecraft:overworld"));
        final ClientLevel target = new ClientLevel(key("minecraft:the_end"));
        final Player player = new Player(1, source);
        final CameraStandEntity stand = new CameraStandEntity(10, source, stack());
        final CameraOnStand camera = new CameraOnStand(player, stand);
        final Viewfinder finder = new Viewfinder(camera);

        ClientEnvironment() {
            mc.player = player; mc.connection = new ClientPacketListener(); mc.camera = stand;
            player.activeCamera = camera; stand.controller = player;
            CameraClient.viewfinder = finder; CameraClient.controlsOpen = true;
        }

        void transfer() { player.world = target; }

        void expectReleased() {
            check(CameraClient.viewfinder() == null, "transported operator viewfinder is removed");
            check(player.activeCamera == null && player.removes == 1, "retained Exposure active camera is cleared exactly once");
            check(!finder.open && !CameraClient.controlsOpen, "stand controls and viewfinder close to restore turning");
            check(mc.getCameraEntity() == player, "render camera returns to player to restore movement");
            check(CameraClient.deactivatePackets == 0, "delayed cleanup sends no generic camera deactivate packet");
            tick(2);
            check(player.removes == 1, "completed cleanup is idempotent");
        }

        void expectPreserved(Camera expected, Viewfinder expectedFinder, Object renderCamera) {
            check(player.activeCamera == expected && player.removes == 0, "valid/new camera ownership is preserved");
            check(CameraClient.viewfinder() == expectedFinder && expectedFinder.open && CameraClient.controlsOpen,
                    "valid/new viewfinder and controls remain open");
            check(mc.getCameraEntity() == renderCamera, "valid/new render camera is preserved");
            check(CameraClient.deactivatePackets == 0, "valid camera cleanup sends no generic deactivate packet");
        }
    }

    private static final class ServerEnvironment {
        final MinecraftServer server = new MinecraftServer();
        final ServerLevel source = new ServerLevel(key("minecraft:overworld"), 1);
        final ServerLevel target = new ServerLevel(key("minecraft:the_end"), 1);
        final ServerPlayer player = player(1);
        final CameraStandEntity stand = new CameraStandEntity(10, source, stack());
        final RemoteCaptureContext remote = new RemoteCaptureContext(stand, target);

        ServerEnvironment() {
            server.levels.put(source.dimension(), source); server.levels.put(target.dimension(), target);
            stand.controller = player; player.activeCamera = new CameraOnStand(player, stand);
        }

        ServerPlayer player(int id) {
            var player = new ServerPlayer(id, UUID.randomUUID(), server, source);
            server.players.players.put(player.getUUID(), player);
            return player;
        }

        void take(List<ServerPlayer> players) {
            DimensionFilmCapture.teleportStandPlayersAfterPhoto(remote, stand.getCamera(), players);
        }

        void expectTransported(ServerPlayer transported) {
            check(transported.level() == target, "eligible photographed player reaches the original target dimension");
            check(before("close:" + transported.getId(), "teleport:" + transported.getId()), "remote session closes before seamless transfer");
            check(before("teleport:" + transported.getId(), "finish:" + transported.getId()), "remote-scene finish remains after dimension transfer");
        }
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "client-transfer" -> {
                var env = new ClientEnvironment(); env.transfer(); tick(1); env.expectReleased();
            }
            case "client-inactive" -> {
                var env = new ClientEnvironment(); env.transfer(); env.stand.camera.active = false;
                check(!CameraClient.isActive() && CameraClient.viewfinder() != null, "inactive item still retains native Exposure viewfinder");
                tick(1); env.expectReleased();
            }
            case "client-reset-entity" -> {
                var env = new ClientEnvironment(); env.transfer(); env.mc.camera = env.player; tick(1); env.expectReleased();
            }
            case "client-valid-stand" -> {
                var env = new ClientEnvironment(); tick(3); env.expectPreserved(env.camera, env.finder, env.stand);
                check(CameraClient.resets == 0, "same-level stand observation never resets the render camera");
            }
            case "client-handheld" -> {
                var env = new ClientEnvironment(); ImmersiveCameraClient.deferExposureStandCameraReset(); env.transfer();
                var hand = new Camera(stack()); var finder = new Viewfinder(hand);
                env.player.activeCamera = hand; CameraClient.viewfinder = finder; env.mc.camera = env.player;
                tick(3); env.expectPreserved(hand, finder, env.player);
            }
            case "client-new-stand" -> {
                var env = new ClientEnvironment(); ImmersiveCameraClient.deferExposureStandCameraReset();
                var next = new CameraStandEntity(11, env.source, stack());
                var camera = new CameraOnStand(env.player, next); var finder = new Viewfinder(camera);
                env.player.activeCamera = camera; CameraClient.viewfinder = finder; env.mc.camera = next;
                tick(3); env.expectPreserved(camera, finder, next);
                check(CameraClient.resets == 0, "old deferred stop cannot reset newly opened same-level stand");
            }
            case "client-same-stand-reopen" -> {
                var env = new ClientEnvironment(); ImmersiveCameraClient.deferExposureStandCameraReset();
                var camera = new CameraOnStand(env.player, env.stand); var finder = new Viewfinder(camera);
                env.player.activeCamera = camera; CameraClient.viewfinder = finder;
                tick(3); env.expectPreserved(camera, finder, env.stand);
                check(CameraClient.resets == 0, "old deferred stop cannot reset a newly valid viewfinder on the same stand");
            }
            case "client-new-stand-after-transfer" -> {
                var env = new ClientEnvironment(); ImmersiveCameraClient.stopRemoteScene(); env.transfer();
                var next = new CameraStandEntity(11, env.target, stack());
                var camera = new CameraOnStand(env.player, next); var finder = new Viewfinder(camera);
                env.player.activeCamera = camera; CameraClient.viewfinder = finder; env.mc.camera = next;
                tick(4); env.expectPreserved(camera, finder, next);
                check(CameraClient.resets == 0, "old transfer-finish marker cannot force-reset a new target-world stand");
            }
            case "client-render-camera-replaced" -> {
                var env = new ClientEnvironment(); env.transfer();
                var next = new CameraStandEntity(11, env.target, stack());
                var camera = new CameraOnStand(env.player, next); var finder = new Viewfinder(camera);
                env.player.activeCamera = camera; CameraClient.viewfinder = finder;
                tick(1); env.expectPreserved(camera, finder, env.player);
                check(CameraClient.resets == 1, "orphan old render camera resets without clearing the new valid viewfinder");
            }
            case "client-source-screenshot" -> {
                var env = new ClientEnvironment(); env.transfer(); ImmersiveCameraClient.deferExposureStandCameraReset();
                SourceStandCapture.rendering = true; tick(3);
                env.expectPreserved(env.camera, env.finder, env.stand);
                check(CameraClient.resets == 0, "synchronous source screenshot owns its camera until rendering completes");
                SourceStandCapture.rendering = false; tick(2); env.expectReleased();
            }
            case "client-transfer-deferral" -> {
                var env = new ClientEnvironment(); env.transfer(); ImmersiveCameraClient.stopRemoteScene();
                tick(2); env.expectPreserved(env.camera, env.finder, env.stand);
                tick(1); env.expectReleased();
            }
            case "server-operator" -> {
                var env = new ServerEnvironment(); env.take(List.of(env.player));
                check(env.stand.stops == 1 && before("stop:10", "teleport:1"), "operator is stopped before crossing dimensions");
                check(Events.events.contains("stop-packet:1"), "source stand can still resolve its operator when stop packet is sent");
                check(env.player.removes == 1 && env.player.activeCamera == null && before("remove:1", "teleport:1"),
                        "operator active camera is removed before transfer even after stand item deactivation");
                check(env.stand.controller == null && !env.stand.camera.active, "old stand relinquishes native operator state");
                env.expectTransported(env.player);
            }
            case "server-other-player" -> {
                var env = new ServerEnvironment(); var other = env.player(2); var handheld = new Camera(stack());
                other.activeCamera = handheld; env.take(List.of(other)); env.expectTransported(other);
                check(env.stand.stops == 0 && env.player.removes == 0, "photographed bystander does not end the operator's stand control");
                check(other.removes == 0 && other.activeCamera == handheld, "bystander's handheld camera is not cleared");
            }
            case "server-other-stand" -> {
                var env = new ServerEnvironment(); var another = new CameraStandEntity(11, env.source, stack());
                var camera = new CameraOnStand(env.player, another); env.player.activeCamera = camera;
                env.take(List.of(env.player)); env.expectTransported(env.player);
                check(env.stand.stops == 0 && another.stops == 0 && env.player.removes == 0,
                        "transfer from one camera cannot clear control owned by another stand");
                check(env.player.activeCamera == camera, "unrelated stand active-camera object is preserved server-side");
            }
            case "server-other-operator" -> {
                var env = new ServerEnvironment(); var other = env.player(2);
                var camera = new CameraOnStand(other, env.stand); env.player.activeCamera = camera;
                env.take(List.of(env.player)); env.expectTransported(env.player);
                check(env.stand.stops == 0 && env.player.removes == 0 && env.player.activeCamera == camera,
                        "camera's operator identity must match the transported player before removing its control");
            }
            case "server-inactive" -> {
                var env = new ServerEnvironment(); env.stand.camera.active = false;
                check(env.player.getActiveExposureCamera() == null && env.player.activeCamera != null,
                        "native active-camera getter filters a retained raw camera with inactive item");
                env.take(List.of(env.player)); env.expectTransported(env.player);
                check(env.stand.stops == 0 && env.player.removes == 0,
                        "server cleanup never claims control of an inactive camera hidden by Exposure's getter");
            }
            case "server-rejected" -> {
                var env = new ServerEnvironment(); var dead = env.player(2); dead.alive = false;
                var removed = env.player(3); removed.removed = true;
                var wrongWorld = env.player(4); wrongWorld.world = env.target;
                var rejected = env.player(5); rejected.accept = false;
                var stale = env.player(6); env.server.players.players.remove(stale.getUUID());
                env.player.accept = false; env.take(List.of(env.player, dead, removed, wrongWorld, rejected, stale));
                check(Events.events.isEmpty() && env.stand.stops == 0 && env.player.removes == 0,
                        "ineligible players neither transfer nor lose camera control");
                DimensionFilmCapture.playerFilm = false; env.player.accept = true; env.take(List.of(env.player));
                check(Events.events.isEmpty(), "ordinary film cannot enter dimension-film transfer cleanup");
            }
            case "server-redstone" -> {
                var env = new ServerEnvironment(); env.stand.controller = null; env.player.activeCamera = null;
                env.take(List.of(env.player)); env.expectTransported(env.player);
                check(env.stand.stops == 0 && env.player.removes == 0, "redstone transfer without native operator preserves its source-photo path");
            }
            default -> throw new AssertionError(args[0]);
        }
        System.out.println("PASS: " + args[0] + " (" + checks + " checks)");
    }

    private static ResourceKey<Level> key(String name) { return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(name)); }
    private static ItemStack stack() {
        var result = new ItemStack(new Item(ResourceLocation.parse("exposure:camera"))); result.active = true; return result;
    }
    private static void tick(int count) { for (int i = 0; i < count; i++) ImmersiveCameraClient.onTick(new ClientTickEvent.Post()); }
    private static boolean before(String first, String second) {
        int earlier = Events.events.indexOf(first), later = Events.events.indexOf(second);
        return earlier >= 0 && later >= 0 && earlier < later;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}

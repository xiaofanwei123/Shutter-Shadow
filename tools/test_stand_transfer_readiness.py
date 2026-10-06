"""Verify that player film proceeds with an entirely unavailable destination world."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from test_camera_route_identity import declaration


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/main/java/com/xfw/shuttershadow/network/RemoteStandPreparation.java"
BASELINE = ROOT / "tools/fixtures/stand-transfer-before.zip"

HARNESS = r"""
import java.util.*;

public class StandTransferReadinessTest {
    static int checks;
    static class Server {}
    static class ServerPlayer {
        final Server server = new Server();
        Server getServer() { return server; }
    }
    static class ExposureVisibility {
        static List<ServerPlayer> playersInFrame(Object stand, ItemStack camera) { return List.of(); }
    }
    static class ItemStack { boolean mob, player; }
    record BlockPos(int x, int y, int z) {
        int getX() { return x; } int getZ() { return z; }
        static BlockPos containing(BlockPos position) { return position; }
    }
    static class Entity { BlockPos blockPosition() { return new BlockPos(-17, 128, 33); } }
    static class Level { String dimension() { return "minecraft:the_end"; } }
    static class Remote {
        final Level level = new Level();
        int candidateQueries, destinations;
        Entity asHolderEntity() { return new Entity(); }
        Level level() { return level; }
        List<ServerPlayer> playersInFrame(ItemStack camera) {
            candidateQueries++; return List.of(new ServerPlayer());
        }
        BlockPos targetPosition(ServerPlayer player) {
            destinations++; return new BlockPos(-17, 128, 33);
        }
    }
    record ChunkLoader(String dimension, int x, int z, int radius) {
        static boolean loaded;
        static int queries;
        boolean isFullyLoaded(Server server) { queries++; return loaded; }
    }
    static class ChunkLoading {
        static final Set<ChunkLoader> active = Collections.newSetFromMap(new IdentityHashMap<>());
        static int registrations, removals;
        static void addGlobalChunkLoader(Server server, ChunkLoader loader) {
            registrations++; active.add(loader);
        }
        static void removeGlobalChunkLoader(Server server, ChunkLoader loader) {
            removals++;
            if (!active.remove(loader)) throw new AssertionError("loader identity not retained for release");
        }
    }
    static class MobDimensionFilmCapture {
        static boolean hasMobDimensionFilm(ItemStack camera) { return camera.mob; }
    }
    static class DimensionFilmCapture {
        static boolean hasPlayerDimensionFilm(ItemStack camera) { return camera.player; }
        static boolean acceptsStandTeleport(ServerPlayer player) { return true; }
    }
    static class ShuttershadowConfig {
        static int radius = 16;
        static int mobCaptureRadius() { return radius; }
    }
    static class McHelper { static int getPlayerLoadDistance(ServerPlayer player) { return 32; } }
    static class RemoteCameraSession {
        static List<ServerPlayer> syncedCapturePlayers(long sequence, List<ServerPlayer> players) { return players; }
    }
    static class Pending {
        Remote remote = new Remote();
        Object stand = new Object();
        final ItemStack camera = new ItemStack();
        final ServerPlayer player = new ServerPlayer();
        final Set<ChunkLoader> transferLoaders = new HashSet<>();
        boolean sourceCapture, discardImage;
        long sequence;
        // PRODUCTION_METHODS
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) {
        switch (args[0]) {
            case "player-unloaded" -> {
                for (boolean redstone : new boolean[]{false, true}) {
                    for (boolean noImage : new boolean[]{false, true}) {
                        Pending shot = new Pending(); shot.camera.player = true;
                        shot.sourceCapture = redstone; shot.discardImage = noImage;
                        check(shot.mobChunksReady(), "player transfer must proceed without destination chunks");
                        check(shot.remote.candidateQueries == 0 && shot.remote.destinations == 0,
                                "readiness does not inspect player destinations");
                        check(shot.transferLoaders.isEmpty(), "player film creates no transfer preload");
                    }
                }
                check(ChunkLoader.queries == 0 && ChunkLoading.registrations == 0,
                        "player film neither waits for FULL chunks nor adds destination tickets");
            }
            case "ordinary-film" -> {
                Pending shot = new Pending();
                check(shot.mobChunksReady(), "ordinary film does not wait for target chunks");
                shot.remote = null; shot.camera.mob = true;
                check(shot.mobChunksReady(), "absent remote context creates no preparation wait");
                check(ChunkLoader.queries == 0 && ChunkLoading.registrations == 0,
                        "non-transfer paths do not touch destination loaders");
            }
            case "mob-data" -> {
                Pending shot = new Pending(); shot.camera.mob = true;
                check(!shot.mobChunksReady(), "mob scan still waits for actual loaded entities");
                check(ChunkLoading.registrations == 1 && shot.transferLoaders.size() == 1,
                        "mob scan registers one loader");
                ChunkLoader loader = shot.transferLoaders.iterator().next();
                check(loader.x() == -2 && loader.z() == 2 && loader.radius() == 1,
                        "mob range keeps signed chunk coordinates and configured small radius");
                check(!shot.mobChunksReady() && ChunkLoading.registrations == 1,
                        "repeated preparation reuses the registered loader");
                ChunkLoader.loaded = true;
                check(shot.mobChunksReady(), "loaded mob data releases preparation");
                shot.releaseTransferLoaders();
                check(shot.transferLoaders.isEmpty() && ChunkLoading.active.isEmpty() && ChunkLoading.removals == 1,
                        "completion releases the exact loader instance");
                shot.releaseTransferLoaders();
                check(ChunkLoading.removals == 1, "repeated cleanup is harmless");
            }
            default -> throw new AssertionError(args[0]);
        }
        System.out.println("PASS: " + args[0] + " (" + checks + " checks)");
    }
}
"""


class StandTransferReadinessTests(unittest.TestCase):
    def test_production_readiness(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertIsNotNone(javac)
        self.assertIsNotNone(java)
        with tempfile.TemporaryDirectory(prefix="shuttershadow-no-player-preload-") as temporary:
            folder = Path(temporary)
            harness = folder / "StandTransferReadinessTest.java"
            methods = "\n".join(declaration(SOURCE, signature) for signature in (
                "private boolean mobChunksReady", "private List<ServerPlayer> candidatesForPhoto",
                "private void releaseTransferLoaders"))
            harness.write_text(HARNESS.replace("// PRODUCTION_METHODS", methods), encoding="utf-8")

            def compile_harness():
                result = subprocess.run([javac, "-encoding", "UTF-8", "--release", "21", "-d", str(folder), str(harness)],
                                        capture_output=True, text=True, encoding="utf-8", errors="replace")
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

            def execute(scenario):
                return subprocess.run([java, "-ea", "-cp", str(folder), "StandTransferReadinessTest", scenario],
                                      capture_output=True, text=True, encoding="utf-8", errors="replace")

            compile_harness()
            for scenario in ("player-unloaded", "ordinary-film", "mob-data"):
                result = execute(scenario)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                print(result.stdout.strip(), flush=True)

            with zipfile.ZipFile(BASELINE) as archive:
                before = folder / "Before.java"
                before.write_bytes(archive.read("src/main/java/com/xfw/shuttershadow/network/RemoteStandPreparation.java"))
            baseline = declaration(before, "private boolean transferChunksReady").replace(
                "transferChunksReady", "mobChunksReady", 1)
            harness.write_text(HARNESS.replace("// PRODUCTION_METHODS", methods.replace(
                declaration(SOURCE, "private boolean mobChunksReady"), baseline)), encoding="utf-8")
            compile_harness()
            result = execute("player-unloaded")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("player transfer must proceed without destination chunks", result.stderr)
            print("BASELINE reproduced: player transfer waited for unavailable destination chunks", flush=True)


if __name__ == "__main__":
    unittest.main()

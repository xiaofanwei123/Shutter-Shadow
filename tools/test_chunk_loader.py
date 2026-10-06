"""Execute public loading/region sources and production release methods without starting Minecraft."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/xfw/shuttershadow"
API = "com.xfw.shuttershadow.api"


def declaration(signature):
    text = (JAVA / "core/chunk_loading/RemoteChunkTracking.java").read_text(encoding="utf-8")
    start = text.index(signature)
    end = text.index("{", start) + 1
    depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


FIXTURES = {
    "net.minecraft.resources.ResourceKey": "public record ResourceKey<T>(String location) {}",
    "net.minecraft.world.level.Level": "public class Level {}",
    "net.minecraft.server.MinecraftServer": """public class MinecraftServer {
        public boolean onThread = true;
        public final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, net.minecraft.server.level.ServerLevel> levels = new java.util.HashMap<>();
        public final PlayerList players = new PlayerList();
        public boolean isSameThread() { return onThread; }
        public net.minecraft.server.level.ServerLevel getLevel(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) { return levels.get(dimension); }
        public PlayerList getPlayerList() { return players; }
        public static class PlayerList {
            public final java.util.Map<java.util.UUID, net.minecraft.server.level.ServerPlayer> entries = new java.util.HashMap<>();
            public net.minecraft.server.level.ServerPlayer getPlayer(java.util.UUID id) { return entries.get(id); }
        }
    }""",
    "net.minecraft.server.level.ServerLevel": "public class ServerLevel {}",
    "net.minecraft.server.level.ServerPlayer": """public class ServerPlayer {
        private final net.minecraft.server.MinecraftServer server;
        private final java.util.UUID id;
        public boolean removed;
        public ServerPlayer(net.minecraft.server.MinecraftServer server, java.util.UUID id) { this.server = server; this.id = id; }
        public net.minecraft.server.MinecraftServer getServer() { return server; }
        public java.util.UUID getUUID() { return id; }
        public boolean isRemoved() { return removed; }
    }""",
    "net.minecraft.world.level.ChunkPos": "public record ChunkPos(int x, int z) {}",
    "com.xfw.shuttershadow.util.McHelper": """public class McHelper {
        public static final java.util.Set<net.minecraft.world.level.ChunkPos> missingChunks = new java.util.HashSet<>();
        public static final java.util.Set<net.minecraft.world.level.ChunkPos> missingEntities = new java.util.HashSet<>();
        public static final java.util.List<net.minecraft.world.level.ChunkPos> visits = new java.util.ArrayList<>();
        public static boolean isServerChunkFullyLoaded(net.minecraft.server.level.ServerLevel world, net.minecraft.world.level.ChunkPos pos) {
            visits.add(pos);
            return !missingChunks.contains(pos) && !missingEntities.contains(pos);
        }
    }""",
    "com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking": """
        import com.xfw.shuttershadow.api.ChunkLoader;
        import net.minecraft.server.MinecraftServer;
        import net.minecraft.server.level.ServerPlayer;
        public class RemoteChunkTracking {
            public static final java.util.List<ChunkLoader> additionalChunkLoaders = new java.util.ArrayList<>();
            public static final java.util.Map<ServerPlayer, PlayerChunkLoading> playerInfoMap = new java.util.IdentityHashMap<>();
            public static class PlayerChunkLoading {
                public final java.util.List<ChunkLoader> additionalChunkLoaders = new java.util.ArrayList<>();
                public boolean shouldUpdateImmediately;
            }
            public static PlayerChunkLoading getPlayerInfo(ServerPlayer player) { return playerInfoMap.computeIfAbsent(player, key -> new PlayerChunkLoading()); }
            public static void addGlobalAdditionalChunkLoader(MinecraftServer server, ChunkLoader loader) { additionalChunkLoaders.add(loader); }
    """ + "\n".join(declaration(signature) for signature in (
        "public static void removeGlobalAdditionalChunkLoader(",
        "public static void addPerPlayerAdditionalChunkLoader(",
        "public static void removePerPlayerAdditionalChunkLoader(",
    )) + "\n}",
}

HARNESS = """
package com.xfw.shuttershadow.api;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import com.xfw.shuttershadow.util.McHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public class ChunkLoaderTest {
    private static int checks;
    private static final ResourceKey<Level> DIMENSION = new ResourceKey<>("target");
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void reject(Class<? extends RuntimeException> type, Runnable call) {
        try { call.run(); }
        catch (RuntimeException ex) { check(type.isInstance(ex), "wrong rejection: " + ex); return; }
        throw new AssertionError("invalid request was accepted");
    }
    public static void main(String[] args) {
        String scenario = args[0];
        MinecraftServer server = new MinecraftServer();
        server.levels.put(DIMENSION, new ServerLevel());
        int radius = scenario.equals("zero") ? 0 : 33;
        ChunkLoader loader = new ChunkLoader(DIMENSION, -1000, 1000, radius);
        ChunkPos first = new ChunkPos(-1033, 967);
        ChunkPos last = new ChunkPos(-967, 1033);
        ServerPlayer player = new ServerPlayer(server, java.util.UUID.randomUUID());
        server.players.entries.put(player.getUUID(), player);
        switch (scenario) {
            case "first-missing":
                McHelper.missingChunks.add(first);
                check(!loader.isFullyLoaded(server), "missing first chunk approved capture");
                check(McHelper.visits.size() == 1, "did not stop at first missing chunk");
                break;
            case "last-missing":
                McHelper.missingChunks.add(last);
                check(!loader.isFullyLoaded(server), "missing final corner approved capture");
                check(McHelper.visits.size() == 4489, "skipped part of the square");
                break;
            case "entities":
                McHelper.missingEntities.add(first);
                check(!loader.isFullyLoaded(server), "pending entities approved capture");
                McHelper.missingEntities.clear();
                check(loader.isFullyLoaded(server), "entity completion remained blocked");
                break;
            case "all-ready":
                check(loader.isFullyLoaded(server), "fully loaded square was blocked");
                check(McHelper.visits.size() == 4489, "wrong square size");
                check(new java.util.HashSet<>(McHelper.visits).size() == 4489, "duplicate chunk visits");
                check(McHelper.visits.getFirst().equals(first) && McHelper.visits.getLast().equals(last), "wrong corners");
                break;
            case "recheck":
                check(loader.isFullyLoaded(server), "initial ready square was blocked");
                McHelper.missingChunks.add(last);
                check(!loader.isFullyLoaded(server), "reused stale ready state");
                McHelper.missingChunks.clear();
                check(loader.isFullyLoaded(server), "reload remained blocked");
                break;
            case "zero":
                check(loader.isFullyLoaded(server), "ready center was blocked");
                check(McHelper.visits.equals(java.util.List.of(new ChunkPos(-1000, 1000))), "zero radius missed center");
                McHelper.missingChunks.add(new ChunkPos(-1000, 1000));
                check(!loader.isFullyLoaded(server), "missing center approved capture");
                break;
            case "bounds":
                reject(NullPointerException.class, () -> new ChunkLoader(null, 0, 0, 0));
                reject(IllegalArgumentException.class, () -> new ChunkLoader(DIMENSION, 0, 0, -1));
                reject(IllegalArgumentException.class, () -> new ChunkLoader(DIMENSION, 0, 0, Integer.MAX_VALUE));
                reject(IllegalArgumentException.class, () -> new ChunkLoader(DIMENSION, Integer.MAX_VALUE, 0, 1));
                reject(IllegalArgumentException.class, () -> new ChunkLoader(DIMENSION, Integer.MIN_VALUE, 0, 1));
                reject(IllegalArgumentException.class, () -> new ChunkLoader(DIMENSION, 0, Integer.MAX_VALUE, 1));
                reject(IllegalArgumentException.class, () -> new ChunkLoader(DIMENSION, 0, Integer.MIN_VALUE, 1));
                check(new ChunkLoader(DIMENSION, Integer.MAX_VALUE, Integer.MIN_VALUE, 0).radius() == 0, "valid extreme center rejected");
                break;
            case "enumeration":
                java.util.Map<ChunkPos, Integer> positions = new java.util.HashMap<>();
                new ChunkLoader(DIMENSION, -1000, 1000, 1).foreachChunkPos((dimension, x, z, distance) -> {
                    check(dimension == DIMENSION, "enumeration lost dimension identity");
                    positions.put(new ChunkPos(x, z), distance);
                });
                check(positions.size() == 9 && positions.get(new ChunkPos(-1001, 999)) == 1, "wrong enumeration edges");
                check(positions.get(new ChunkPos(-1000, 1000)) == 0, "wrong center distance");
                check(McHelper.visits.isEmpty(), "construction/enumeration performed world queries");
                reject(NullPointerException.class, () -> loader.foreachChunkPos(null));
                break;
            case "identity-release":
                ChunkLoader equal = new ChunkLoader(DIMENSION, -1000, 1000, radius);
                check(loader.equals(equal) && loader != equal, "region equality changed");
                ChunkLoading.addGlobalChunkLoader(server, loader);
                ChunkLoading.addGlobalChunkLoader(server, equal);
                check(RemoteChunkTracking.additionalChunkLoaders.size() == 2, "independent equal registrations collapsed");
                ChunkLoading.removeGlobalChunkLoader(server, new ChunkLoader(DIMENSION, -1000, 1000, radius));
                check(RemoteChunkTracking.additionalChunkLoaders.size() == 2, "release used value equality");
                ChunkLoading.removeGlobalChunkLoader(server, loader);
                check(RemoteChunkTracking.additionalChunkLoaders.size() == 1 && RemoteChunkTracking.additionalChunkLoaders.getFirst() == equal, "release removed another owner");
                ChunkLoading.removeGlobalChunkLoader(server, loader);
                check(RemoteChunkTracking.additionalChunkLoaders.size() == 1, "repeated release changed another registration");
                ChunkLoading.removeGlobalChunkLoader(server, equal);
                check(RemoteChunkTracking.additionalChunkLoaders.isEmpty(), "final release leaked registration");
                break;
            case "scopes":
                ChunkLoading.addGlobalChunkLoader(server, loader);
                check(RemoteChunkTracking.playerInfoMap.isEmpty(), "server-only preload subscribed a player");
                ChunkLoading.addChunkLoaderForPlayer(player, loader);
                var info = RemoteChunkTracking.playerInfoMap.get(player);
                check(info.additionalChunkLoaders.size() == 1 && info.shouldUpdateImmediately, "player subscription was not scheduled");
                check(RemoteChunkTracking.additionalChunkLoaders.size() == 1, "player subscription changed global preload");
                check(loader.isFullyLoaded(server), "server readiness blocked by absent client ack");
                ChunkLoading.removeChunkLoaderForPlayer(player, new ChunkLoader(DIMENSION, -1000, 1000, radius));
                check(info.additionalChunkLoaders.size() == 1, "player release used value equality");
                ChunkLoading.removeChunkLoaderForPlayer(player, loader);
                ChunkLoading.removeChunkLoaderForPlayer(player, loader);
                check(info.additionalChunkLoaders.isEmpty() && RemoteChunkTracking.additionalChunkLoaders.size() == 1, "player release removed global registration");
                break;
            case "thread":
                server.onThread = false;
                reject(IllegalStateException.class, () -> ChunkLoading.addGlobalChunkLoader(server, loader));
                reject(IllegalStateException.class, () -> ChunkLoading.removeGlobalChunkLoader(server, loader));
                reject(IllegalStateException.class, () -> ChunkLoading.addChunkLoaderForPlayer(player, loader));
                reject(IllegalStateException.class, () -> ChunkLoading.removeChunkLoaderForPlayer(player, loader));
                reject(IllegalStateException.class, () -> loader.isFullyLoaded(server));
                check(RemoteChunkTracking.additionalChunkLoaders.isEmpty() && RemoteChunkTracking.playerInfoMap.isEmpty() && McHelper.visits.isEmpty(), "wrong-thread request mutated backend");
                break;
            case "missing-target":
                ChunkLoading.addGlobalChunkLoader(server, loader);
                ChunkLoading.addChunkLoaderForPlayer(player, loader);
                server.levels.clear();
                reject(IllegalArgumentException.class, () -> ChunkLoading.addGlobalChunkLoader(server, loader));
                reject(IllegalArgumentException.class, () -> ChunkLoading.addChunkLoaderForPlayer(player, loader));
                check(!loader.isFullyLoaded(server) && McHelper.visits.isEmpty(), "missing dimension was ready or queried");
                ChunkLoading.removeGlobalChunkLoader(server, loader);
                ChunkLoading.removeChunkLoaderForPlayer(player, loader);
                check(RemoteChunkTracking.additionalChunkLoaders.isEmpty() && RemoteChunkTracking.playerInfoMap.get(player).additionalChunkLoaders.isEmpty(), "dimension removal prevented release");
                break;
            case "player-lifecycle":
                ChunkLoading.addChunkLoaderForPlayer(player, loader);
                var originalInfo = RemoteChunkTracking.playerInfoMap.get(player);
                ServerPlayer replacement = new ServerPlayer(server, player.getUUID());
                server.players.entries.put(player.getUUID(), replacement);
                reject(IllegalArgumentException.class, () -> ChunkLoading.addChunkLoaderForPlayer(player, loader));
                ChunkLoading.addChunkLoaderForPlayer(replacement, loader);
                ChunkLoading.removeChunkLoaderForPlayer(player, loader);
                check(originalInfo.additionalChunkLoaders.isEmpty() && RemoteChunkTracking.playerInfoMap.get(replacement).additionalChunkLoaders.size() == 1, "old player release affected respawn replacement");
                replacement.removed = true;
                reject(IllegalArgumentException.class, () -> ChunkLoading.addChunkLoaderForPlayer(replacement, loader));
                server.players.entries.clear();
                ChunkLoading.removeChunkLoaderForPlayer(replacement, loader);
                check(RemoteChunkTracking.playerInfoMap.get(replacement).additionalChunkLoaders.isEmpty(), "disconnect prevented release");
                RemoteChunkTracking.playerInfoMap.clear();
                ChunkLoading.removeChunkLoaderForPlayer(player, loader);
                ChunkLoading.removeChunkLoaderForPlayer(replacement, loader);
                check(RemoteChunkTracking.playerInfoMap.isEmpty(), "release recreated stale tracking after core cleanup");
                break;
            case "null-requests":
                reject(NullPointerException.class, () -> ChunkLoading.addGlobalChunkLoader(null, loader));
                reject(NullPointerException.class, () -> ChunkLoading.removeGlobalChunkLoader(null, loader));
                reject(NullPointerException.class, () -> ChunkLoading.addGlobalChunkLoader(server, null));
                reject(NullPointerException.class, () -> ChunkLoading.removeGlobalChunkLoader(server, null));
                reject(NullPointerException.class, () -> ChunkLoading.addChunkLoaderForPlayer(null, loader));
                reject(NullPointerException.class, () -> ChunkLoading.removeChunkLoaderForPlayer(null, loader));
                reject(NullPointerException.class, () -> ChunkLoading.addChunkLoaderForPlayer(player, null));
                reject(NullPointerException.class, () -> ChunkLoading.removeChunkLoaderForPlayer(player, null));
                reject(NullPointerException.class, () -> loader.isFullyLoaded(null));
                check(RemoteChunkTracking.additionalChunkLoaders.isEmpty() && RemoteChunkTracking.playerInfoMap.isEmpty(), "null request mutated backend");
                break;
            default: throw new AssertionError("Unknown scenario: " + scenario);
        }
        System.out.println("PASS: " + scenario + " (" + checks + " checks)");
    }
}
"""


class ChunkLoaderTests(unittest.TestCase):
    def test_production_loading_api(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertTrue(javac and java, "JDK 21 is required")
        with tempfile.TemporaryDirectory(prefix="shuttershadow-chunk-loader-") as temporary:
            folder = Path(temporary)
            sources = []
            for name, body in FIXTURES.items():
                path = folder / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf-8")
                sources.append(str(path))
            harness = folder / "ChunkLoaderTest.java"
            harness.write_text(HARNESS, encoding="utf-8")
            sources += [str(harness), str(JAVA / "api/ChunkLoader.java"), str(JAVA / "api/ChunkLoading.java")]
            compilation = subprocess.run([javac, "-encoding", "UTF-8", "--release", "21", "-d", str(folder), *sources],
                                         capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(compilation.returncode, 0, compilation.stdout + compilation.stderr)
            for scenario in ("first-missing", "last-missing", "entities", "all-ready", "recheck", "zero",
                             "bounds", "enumeration", "identity-release", "scopes", "thread", "missing-target",
                             "player-lifecycle", "null-requests"):
                with self.subTest(scenario=scenario):
                    execution = subprocess.run([java, "-ea", "-cp", str(folder), API + ".ChunkLoaderTest", scenario],
                                               capture_output=True, text=True, encoding="utf-8", errors="replace")
                    self.assertEqual(execution.returncode, 0, execution.stdout + execution.stderr)
                    print(execution.stdout.strip(), flush=True)


if __name__ == "__main__":
    unittest.main()

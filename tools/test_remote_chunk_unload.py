"""Exercise production chunk removal, notification order and render-section reset."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

from test_remote_view_area_resources import declaration


ROOT = Path(__file__).resolve().parents[1]
CHUNK_MAP = ROOT / "src/main/java/com/xfw/shuttershadow/core/chunk_loading/RemoteClientChunkMap.java"
VIEW_AREA = ROOT / "src/main/java/com/xfw/shuttershadow/core/render/RemoteViewArea.java"


FIXTURES = {
    "fixture.Trace": """public class Trace {
        public static final java.util.List<String> EVENTS = new java.util.ArrayList<>();
        public static void event(String event) { EVENTS.add(event); }
    }""",
    "net.minecraft.resources.ResourceKey": "public record ResourceKey<T>(String name) {}",
    "net.minecraft.world.level.Level": """public class Level {
        private final net.minecraft.resources.ResourceKey<Level> dimension;
        public Level(String dimension) { this.dimension = new net.minecraft.resources.ResourceKey<>(dimension); }
        public net.minecraft.resources.ResourceKey<Level> dimension() { return dimension; }
        public void unload(net.minecraft.world.level.chunk.LevelChunk chunk) { fixture.Trace.event("world"); }
    }""",
    "net.minecraft.world.level.ChunkPos": """public class ChunkPos {
        public final int x, z;
        public ChunkPos(int x, int z) { this.x = x; this.z = z; }
        public static long asLong(int x, int z) { return Integer.toUnsignedLong(x) | ((long) z << 32); }
        public long toLong() { return asLong(x, z); }
    }""",
    "net.minecraft.world.level.chunk.LevelChunk": """public record LevelChunk(
            net.minecraft.world.level.Level getLevel, net.minecraft.world.level.ChunkPos getPos) {}""",
    "net.minecraft.client.renderer.ViewArea": "public class ViewArea {}",
    "net.minecraft.client.renderer.LevelRenderer": """public record LevelRenderer(
            net.minecraft.client.renderer.ViewArea area) implements com.xfw.shuttershadow.access.IEWorldRenderer {
        public net.minecraft.client.renderer.ViewArea ip_getBuiltChunkStorage() { return area; }
    }""",
    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher": """public class SectionRenderDispatcher {
        public static class RenderSection implements com.xfw.shuttershadow.access.IERenderSection {
            public int resets;
            public void portal_fullyReset() { resets++; fixture.Trace.event("mesh"); }
        }
    }""",
    "com.xfw.shuttershadow.access.IEWorldRenderer": """public interface IEWorldRenderer {
        net.minecraft.client.renderer.ViewArea ip_getBuiltChunkStorage();
    }""",
    "com.xfw.shuttershadow.access.IERenderSection": "public interface IERenderSection { void portal_fullyReset(); }",
    "com.xfw.shuttershadow.core.ClientWorldLoader": """public class ClientWorldLoader {
        public static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
            net.minecraft.client.renderer.LevelRenderer> WORLD_RENDERER_MAP = new java.util.HashMap<>();
    }""",
    "com.xfw.shuttershadow.core.PlatformBridge": """public class PlatformBridge {
        public static java.util.function.Consumer<net.minecraft.world.level.chunk.LevelChunk> check = chunk -> {};
        public static void postClientChunkUnloadEvent(net.minecraft.world.level.chunk.LevelChunk chunk) {
            check.accept(chunk); fixture.Trace.event("platform");
        }
    }""",
    "com.xfw.shuttershadow.compat.SodiumInterface": """public class SodiumInterface {
        public static final Invoker invoker = new Invoker();
        public static class Invoker {
            public void onClientChunkUnloaded(net.minecraft.world.level.Level world, int x, int z) {
                fixture.Trace.event("sodium");
            }
        }
    }""",
}


def production_fixtures():
    chunks = CHUNK_MAP.read_text(encoding="utf-8")
    view = VIEW_AREA.read_text(encoding="utf-8")
    return {
        "com.xfw.shuttershadow.core.chunk_loading.RemoteClientChunkMap": """
            import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
            import net.minecraft.world.level.ChunkPos;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.chunk.LevelChunk;
            import org.apache.commons.lang3.Validate;
            import com.xfw.shuttershadow.core.PlatformBridge;
            import com.xfw.shuttershadow.compat.SodiumInterface;
            import com.xfw.shuttershadow.core.render.RemoteViewArea;
            import java.util.function.Consumer;
            public class RemoteClientChunkMap {
                public final Thread mainThread = Thread.currentThread();
                public final Level level;
                public final Long2ObjectOpenHashMap<LevelChunk> chunkMapForMainThread = new Long2ObjectOpenHashMap<>();
                public final Long2ObjectOpenHashMap<LevelChunk> chunkMapForOtherThreads = new Long2ObjectOpenHashMap<>();
                public RemoteClientChunkMap(Level level) { this.level = level; }
        """ + declaration(chunks, "public void drop(") + declaration(chunks, "public void modifyChunkMap(") + "}",
        "com.xfw.shuttershadow.core.render.RemoteViewArea": """
            import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.ChunkPos;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.minecraft.client.renderer.LevelRenderer;
            import net.minecraft.client.renderer.ViewArea;
            import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
            import com.xfw.shuttershadow.access.IEWorldRenderer;
            import com.xfw.shuttershadow.access.IERenderSection;
            import com.xfw.shuttershadow.core.ClientWorldLoader;
            public class RemoteViewArea extends ViewArea {
                public final Long2ObjectOpenHashMap<Column> columnMap = new Long2ObjectOpenHashMap<>();
        """ + declaration(view, "public static class Column")
        + declaration(view, "public static void onClientChunkUnload(")
        + declaration(view, "public void onChunkUnload(") + "}",
    }


class RemoteChunkUnloadTests(unittest.TestCase):
    def test_unload_order_and_section_reset(self):
        fallback = Path("C:/Program Files/Zulu/zulu-21/bin")
        java = str(fallback / "java.exe") if fallback.is_dir() else shutil.which("java")
        javac = str(fallback / "javac.exe") if fallback.is_dir() else shutil.which("javac")
        self.assertTrue(java and javac, "Java 21 is required")
        artifacts = [Path(line.replace("\\\\", "\\")) for line in
                     (ROOT / "build/moddev/clientLegacyClasspath.txt").read_text().splitlines()]
        jars = [path for path in artifacts if path.name.startswith(("fastutil-", "commons-lang3-"))]
        self.assertEqual(len(jars), 2)
        with tempfile.TemporaryDirectory(prefix="shuttershadow-chunk-unload-test-") as folder:
            work = Path(folder)
            sources = work / "src"
            classes = work / "classes"
            definitions = dict(FIXTURES, **production_fixtures())
            for name, body in definitions.items():
                path = sources / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf-8")
            test = sources / "RemoteChunkUnloadTest.java"
            test.write_text((ROOT / "tools/tests/RemoteChunkUnloadTest.java").read_text(encoding="utf-8"), encoding="utf-8")
            classpath = os.pathsep.join(map(str, jars))
            result = subprocess.run([javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes),
                                     *map(str, sources.rglob("*.java"))],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([java, "-cp", str(classes) + os.pathsep + classpath, "RemoteChunkUnloadTest"],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()

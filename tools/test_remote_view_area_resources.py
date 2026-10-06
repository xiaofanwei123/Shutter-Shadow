"""Run the production section ownership and purge methods without Minecraft or OpenGL."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/main/java/com/xfw/shuttershadow/core/render/RemoteViewArea.java"


def declaration(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


FIXTURES = {
    "net.minecraft.client.Minecraft": """public class Minecraft {
        private static final Minecraft INSTANCE = new Minecraft();
        private final Profiler profiler = new Profiler();
        public static Minecraft getInstance() { return INSTANCE; }
        public Profiler getProfiler() { return profiler; }
        public static class Profiler { public void push(String name) {} public void pop() {} }
    }""",
    "net.minecraft.world.level.Level": "public record Level(int minY, int sections) {}",
    "net.minecraft.world.level.ChunkPos": """public record ChunkPos(int x, int z) {
        public static long asLong(int x, int z) { return Integer.toUnsignedLong(x) | ((long) z << 32); }
        public static int getX(long value) { return (int) value; }
        public static int getZ(long value) { return (int) (value >>> 32); }
        public long toLong() { return asLong(x, z); }
    }""",
    "net.minecraft.util.Mth": "public class Mth { public static int floor(double value) { return (int) Math.floor(value); } }",
    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher": """public class SectionRenderDispatcher {
        public static final java.util.List<RenderSection> CREATED = new java.util.ArrayList<>();
        public class RenderSection {
            public int releases;
            public final int x, y, z;
            public RenderSection(int index, int x, int y, int z) {
                this.x = x; this.y = y; this.z = z; CREATED.add(this);
            }
            public void releaseBuffers() { releases++; }
        }
    }""",
    "com.xfw.shuttershadow.util.McHelper": """public class McHelper {
        public static int getMinY(net.minecraft.world.level.Level level) { return level.minY(); }
    }""",
    "com.xfw.shuttershadow.util.Helper": "public class Helper { public static long secondToNano(double seconds) { return (long) (seconds * 1_000_000_000L); } }",
    "com.xfw.shuttershadow.core.GcMonitor": "public class GcMonitor { public static boolean isMemoryNotEnough() { return false; } }",
    "com.xfw.shuttershadow.core.CoreSettings": """public class CoreSettings {
        public static final Tasks PRE_GAME_RENDER_TASK_LIST = new Tasks();
        public static class Tasks {
            public final java.util.List<java.util.function.BooleanSupplier> pending = new java.util.ArrayList<>();
            public void addTask(java.util.function.BooleanSupplier task) { pending.add(task); }
            public void tick() { pending.removeIf(java.util.function.BooleanSupplier::getAsBoolean); }
            public void drain() {
                int remaining = 1000;
                while (!pending.isEmpty() && remaining-- > 0) tick();
                if (!pending.isEmpty()) throw new AssertionError("purge task never completed");
            }
        }
    }""",
}


def production_fixture(text):
    # Fields and constructor below only provide the missing ViewArea/GL environment.
    # Every ownership, coordinate and expiry decision comes verbatim from production.
    methods = (
        "public static class Column", "public static class Preset",
        "public void releaseAllBuffers()", "public void repositionCamera(",
        "private Preset createPresetByChunkPos(", "private void foreachPresetCoveredChunkPoses(",
        "private int getChunkIndex(", "public Column provideColumn(",
        "private Column createColumn(", "private void purge()",
        "private boolean shouldDropPreset(", "public RenderSection rawFetch(",
    )
    imports = """
        import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
        import net.minecraft.client.Minecraft;
        import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
        import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
        import net.minecraft.util.Mth;
        import net.minecraft.world.level.ChunkPos;
        import net.minecraft.world.level.Level;
        import org.apache.commons.lang3.Validate;
        import com.xfw.shuttershadow.core.CoreSettings;
        import com.xfw.shuttershadow.core.GcMonitor;
        import com.xfw.shuttershadow.util.Helper;
        import com.xfw.shuttershadow.util.McHelper;
        import java.util.ArrayDeque;
        import java.util.Arrays;
        import java.util.function.LongConsumer;
    """
    # The old implementation is also executable, so this regression can prove the leak.
    if "private Set<RenderSection> getAllActiveBuiltChunks()" in text:
        imports += "import java.util.Set; import java.util.HashSet;"
        methods += ("private Set<RenderSection> getAllActiveBuiltChunks()",)
    return "package com.xfw.shuttershadow.core.render;\n" + imports + """
        public class RemoteViewArea {
            private final SectionRenderDispatcher factory = new SectionRenderDispatcher();
            private final Long2ObjectOpenHashMap<Column> columnMap = new Long2ObjectOpenHashMap<>();
            private final Long2ObjectOpenHashMap<Preset> presets = new Long2ObjectOpenHashMap<>();
            private boolean isAlive = true;
            public final int minSectionY, endSectionY;
            private final int sectionGridSizeX, sectionGridSizeY, sectionGridSizeZ;
            private final Level level;
            private RenderSection[] sections;
            public RemoteViewArea(int x, int y, int z) {
                sectionGridSizeX = x; sectionGridSizeY = y; sectionGridSizeZ = z;
                minSectionY = 0; endSectionY = y; level = new Level(0, y);
                sections = new RenderSection[x * y * z];
            }
            public int columnsForTest() { return columnMap.size(); }
            public int presetsForTest() { return presets.size(); }
            public boolean aliveForTest() { return isAlive; }
            public void purgeForTest() { purge(); }
    """ + "\n".join(declaration(text, signature) for signature in methods) + "\n}"


class RemoteViewAreaResourceTests(unittest.TestCase):
    def test_production_resource_ownership(self):
        java = shutil.which("java")
        javac = shutil.which("javac")
        fallback = Path("C:/Program Files/Zulu/zulu-21/bin")
        if fallback.is_dir():
            java = str(fallback / "java.exe")
            javac = str(fallback / "javac.exe")
        self.assertTrue(java and javac, "Java 21 is required")
        artifacts = [
            Path(line.replace("\\\\", "\\"))
            for line in (ROOT / "build/moddev/clientLegacyClasspath.txt").read_text().splitlines()
        ]
        jars = [path for path in artifacts if path.name.startswith(("fastutil-", "commons-lang3-"))]
        self.assertEqual(len(jars), 2, "test requires the resolved FastUtil and Commons Lang jars")
        with tempfile.TemporaryDirectory(prefix="shuttershadow-render-resource-test-") as folder:
            work = Path(folder)
            sources = work / "src"
            classes = work / "classes"
            definitions = dict(FIXTURES)
            definitions["com.xfw.shuttershadow.core.render.RemoteViewArea"] = production_fixture(
                SOURCE.read_text(encoding="utf-8"))
            for name, body in definitions.items():
                path = sources / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                if not body.startswith("package "):
                    body = "package " + name.rsplit(".", 1)[0] + ";\n" + body
                path.write_text(body, encoding="utf-8")
            test = sources / "RemoteViewAreaResourceTest.java"
            test.write_text((ROOT / "tools/tests/RemoteViewAreaResourceTest.java").read_text(encoding="utf-8"), encoding="utf-8")
            classpath = os.pathsep.join(map(str, jars))
            compile_result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes),
                 *map(str, sources.rglob("*.java"))],
                capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            result = subprocess.run(
                [java, "-cp", str(classes) + os.pathsep + classpath, "RemoteViewAreaResourceTest"],
                capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()

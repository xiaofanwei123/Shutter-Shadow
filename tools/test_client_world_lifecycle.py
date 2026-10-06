"""Execute production client-world lifecycle methods with injected renderer failures."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/main/java/com/xfw/shuttershadow/core/ClientWorldLoader.java"


def declaration(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def production_fixture(text):
    # Only the missing Minecraft environment and accessors are supplied by fixtures.
    # Creation, disposal, reload and world-switch decisions execute verbatim production.
    methods = (
        "public static boolean getIsCreatingClientWorld()",
        "private static void disposeWorldRenderer(",
        "private static ClientLevel createSecondaryClientWorld(",
        "public static Set<ResourceKey<Level>> getServerDimensions()",
        "public static void _onWorldRendererReloaded()",
        "public static <T> T withSwitchedWorld(",
        "public static void withSwitchedWorld(ClientLevel newWorld, Runnable runnable)",
    )
    return """
        import java.util.*;
        import java.util.function.Supplier;
        class ClientWorldLoader {
            static final Minecraft CLIENT = new Minecraft();
            static final Map<ResourceKey<Level>, ClientLevel> CLIENT_WORLD_MAP = new HashMap<>();
            static final Map<ResourceKey<Level>, LevelRenderer> WORLD_RENDERER_MAP = new HashMap<>();
            static Map<ResourceKey<Level>, ResourceKey<DimensionType>> dimIdToDimTypeId;
            private static boolean isCreatingClientWorld;
            private static boolean isReloadingOtherWorldRenderers;
            static final TestLogger LOGGER = new TestLogger();
            static LevelRenderer getWorldRenderer(ResourceKey<Level> dimension) {
                return WORLD_RENDERER_MAP.get(dimension);
            }
            static ClientLevel createForTest(ResourceKey<Level> dimension) {
                return createSecondaryClientWorld(dimension);
            }
            static boolean reloadingForTest() { return isReloadingOtherWorldRenderers; }
    """ + "\n".join(declaration(text, signature) for signature in methods) + "\n}"


class ClientWorldLifecycleTests(unittest.TestCase):
    def test_production_exception_recovery(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertTrue(javac and java, "Java 21 is required")
        with tempfile.TemporaryDirectory(prefix="shuttershadow-client-world-") as temporary:
            work = Path(temporary)
            production = work / "ClientWorldLoader.java"
            production.write_text(production_fixture(SOURCE.read_text(encoding="utf-8")), encoding="utf-8")
            test = work / "ClientWorldLifecycleTest.java"
            test.write_text((ROOT / "tools/tests/ClientWorldLifecycleTest.java").read_text(encoding="utf-8"), encoding="utf-8")
            compilation = subprocess.run(
                [javac, "-encoding", "UTF-8", "--release", "21", "-d", str(work), str(production), str(test)],
                capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(compilation.returncode, 0, compilation.stdout + compilation.stderr)
            execution = subprocess.run(
                [java, "-ea", "-cp", str(work), "ClientWorldLifecycleTest"],
                capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(execution.returncode, 0, execution.stdout + execution.stderr)
            print(execution.stdout.strip())


if __name__ == "__main__":
    unittest.main()

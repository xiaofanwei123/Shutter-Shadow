"""Exercise production lightmap ownership with native texture-manager lifecycle code."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from zipfile import ZipFile

from test_client_world_lifecycle import declaration


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "src/main/java/com/xfw/shuttershadow/core/render/DimensionRenderHelper.java"
LOADER = ROOT / "src/main/java/com/xfw/shuttershadow/core/ClientWorldLoader.java"
SOURCES = ROOT / "build/moddev/artifacts/neoforge-21.1.252-sources.jar"


def native_fixture():
    # Texture registration/close/release decisions are copied verbatim from the
    # project's resolved 1.21.1 sources; fixtures replace OpenGL and the game only.
    with ZipFile(SOURCES) as archive:
        light = archive.read("net/minecraft/client/renderer/LightTexture.java").decode()
        manager = archive.read("net/minecraft/client/renderer/texture/TextureManager.java").decode()
        dynamic = archive.read("net/minecraft/client/renderer/texture/DynamicTexture.java").decode()
        abstract = archive.read("net/minecraft/client/renderer/texture/AbstractTexture.java").decode()
    light_methods = "\n".join(declaration(light, signature) for signature in (
        "public LightTexture(", "public void close()", "public void tick()"))
    manager_methods = "\n".join(declaration(manager, signature) for signature in (
        "public void register(ResourceLocation path, AbstractTexture texture)",
        "public ResourceLocation register(String name, DynamicTexture texture)",
        "private void safeClose(", "public void release(", "public void close()"))
    return """
        import java.util.*;
        class LightTexture {
            final DynamicTexture lightTexture;
            final NativeImage lightPixels;
            final ResourceLocation lightTextureLocation;
            boolean updateLightTexture;
            float blockLightRedFlicker;
            final GameRenderer renderer;
            final Minecraft minecraft;
    """ + light_methods + """
        }
        class TextureManager {
            final Map<ResourceLocation, AbstractTexture> byPath = new HashMap<>();
            final Set<Tickable> tickableTextures = new HashSet<>();
            final Map<String, Integer> prefixRegister = new HashMap<>();
            static final TestLogger LOGGER = new TestLogger();
            AbstractTexture loadTexture(ResourceLocation path, AbstractTexture texture) { return texture; }
    """ + manager_methods + """
        }
        class DynamicTexture extends AbstractTexture {
            NativeImage pixels;
    """ + declaration(dynamic, "public DynamicTexture(int") + """
            NativeImage getPixels() { return pixels; }
            void upload() { if (pixels == null) throw new AssertionError("disposed texture upload"); }
    """ + declaration(dynamic, "public void close()") + """
        }
        class AbstractTexture {
            int id = TextureUtil.generateTextureId();
            int getId() { return id; }
            public void close() {}
    """ + declaration(abstract, "public void releaseId()") + """
        }
        class ClientWorldLoader {
            static final Map<Integer, DimensionRenderHelper> RENDER_HELPER_MAP = new HashMap<>();
    """ + declaration(LOADER.read_text(encoding="utf-8"), "public static void disposeRenderHelpers()") + "\n}"


class LightmapLifecycleTests(unittest.TestCase):
    def test_native_release_and_production_ownership(self):
        java, javac = shutil.which("java"), shutil.which("javac")
        self.assertTrue(java and javac, "Java 21 is required")
        self.assertTrue(SOURCES.is_file(), "resolved NeoForge sources are required")
        rules = (ROOT / "src/main/resources/META-INF/accesstransformer.cfg").read_text(encoding="utf-8")
        self.assertIn("public net.minecraft.client.renderer.LightTexture lightTextureLocation", rules)
        with tempfile.TemporaryDirectory(prefix="shuttershadow-lightmap-") as directory:
            work = Path(directory)
            helper = "\n".join(line for line in HELPER.read_text(encoding="utf-8").splitlines()
                               if not line.startswith(("package ", "import ")))
            (work / "DimensionRenderHelper.java").write_text(helper, encoding="utf-8")
            (work / "NativeTextures.java").write_text(native_fixture(), encoding="utf-8")
            (work / "LightmapLifecycleTest.java").write_text(
                (ROOT / "tools/tests/LightmapLifecycleTest.java").read_text(encoding="utf-8"), encoding="utf-8")
            compilation = subprocess.run(
                [javac, "--release", "21", "-encoding", "UTF-8", "-d", str(work),
                 *map(str, work.glob("*.java"))], capture_output=True, text=True,
                encoding="utf-8", errors="replace")
            self.assertEqual(compilation.returncode, 0, compilation.stdout + compilation.stderr)
            execution = subprocess.run([java, "-ea", "-cp", str(work), "LightmapLifecycleTest"],
                                       capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(execution.returncode, 0, execution.stdout + execution.stderr)
            print(execution.stdout.strip())


if __name__ == "__main__":
    unittest.main()

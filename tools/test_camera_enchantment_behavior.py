"""Execute camera curse decisions verbatim against controlled game/network fixtures."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from test_client_world_lifecycle import declaration


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/xfw/shuttershadow"


def exposed_method(source, signature):
    # Only visibility and the compile-time MixinExtras local annotation change.
    method = declaration(source, signature).replace("private ", "public ", 1)
    return re.sub(r"@Local\([^)]*\)\s*", "", method)


def production_fixture():
    enchantments = (JAVA / "CameraEnchantments.java").read_text(encoding="utf-8")
    enchantments = "\n".join(line for line in enchantments.splitlines()
                             if not line.startswith(("package ", "import ")))
    hooks = (JAVA / "mixin/exposure/CameraItemRemoteCaptureMixin.java").read_text(encoding="utf-8")
    methods = "\n".join(exposed_method(hooks, signature) for signature in (
        "private void shuttershadow$openInSelfieMode(",
        "private void shuttershadow$commitAfterScreenshot(",
        "private void shuttershadow$mobFilmUploadCallback(",
        "private void shuttershadow$discardExposureImage(",
    ))
    mob = (JAVA / "MobDimensionFilmCapture.java").read_text(encoding="utf-8")
    pending_key = declaration(mob, "private record PendingKey(").replace("private ", "", 1)
    mob_methods = "\n".join(declaration(mob, signature) for signature in (
        "public static boolean expectUpload(", "public static void completeWithoutUpload(",
    ))
    pending_methods = "\n".join(declaration(mob, signature) for signature in (
        "private void release()", "private void transfer()",
    ))
    client = (JAVA / "mixin/exposure/ViewfinderNarcissismMixin.java").read_text(encoding="utf-8")
    return """
        import java.util.*;
        import java.util.function.BiConsumer;
    """ + enchantments.replace("public final class CameraEnchantments", "final class CameraEnchantments") + """
        class CameraHooks {
    """ + methods + """
        }
        class MobDimensionFilmCapture {
    """ + pending_key + """
            static final Map<PendingKey, Pending> PENDING = new HashMap<>();
    """ + mob_methods + """
            static final class Pending {
                final ServerPlayer photographer;
                final ServerLevel source, target;
                final UUID entityId;
                final Vec3 destination = new Vec3();
                final ChunkLoader loader = new ChunkLoader();
                Pending(ServerPlayer photographer, ServerLevel source, ServerLevel target, UUID entityId) {
                    this.photographer = photographer; this.source = source;
                    this.target = target; this.entityId = entityId;
                }
    """ + pending_methods + """
            }
        }
        abstract class ClientHooks {
            protected Camera camera;
            public abstract ViewfinderSelfie selfie();
    """ + "\n".join(exposed_method(client, signature) for signature in (
        "private void shuttershadow$defaultSelfie(",
        "private void shuttershadow$closeDetachedHandheld(",
    )) + "\n}"


class CameraEnchantmentBehaviorTests(unittest.TestCase):
    def test_production_curse_behavior(self):
        java, javac = shutil.which("java"), shutil.which("javac")
        self.assertTrue(java and javac, "Java 21 is required")
        with tempfile.TemporaryDirectory(prefix="shuttershadow-camera-curse-") as folder:
            work = Path(folder)
            (work / "ProductionCameraCurse.java").write_text(production_fixture(), encoding="utf-8")
            (work / "CameraEnchantmentBehaviorTest.java").write_text(
                (ROOT / "tools/tests/CameraEnchantmentBehaviorTest.java").read_text(encoding="utf-8"), encoding="utf-8")
            compilation = subprocess.run(
                [javac, "--release", "21", "-encoding", "UTF-8", "-d", str(work),
                 *map(str, work.glob("*.java"))], capture_output=True, text=True,
                encoding="utf-8", errors="replace")
            self.assertEqual(compilation.returncode, 0, compilation.stdout + compilation.stderr)
            execution = subprocess.run([java, "-ea", "-cp", str(work), "CameraEnchantmentBehaviorTest"],
                                       capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(execution.returncode, 0, execution.stdout + execution.stderr)
            print(execution.stdout.strip())


if __name__ == "__main__":
    unittest.main()

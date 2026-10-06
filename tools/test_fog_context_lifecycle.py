"""Execute production fog context swapping and its actual logout listener."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_client_world_lifecycle import declaration


ROOT = Path(__file__).resolve().parents[1]
RENDER = ROOT / "src/main/java/com/xfw/shuttershadow/core/render"


class FogContextLifecycleTests(unittest.TestCase):
    def test_production_fog_connection_lifecycle(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertTrue(javac and java, "Java 21 is required")
        with tempfile.TemporaryDirectory(prefix="shuttershadow-fog-context-") as temporary:
            work = Path(temporary)
            for name in ("FogRendererContext", "StaticFieldsSwappingManager"):
                text = (RENDER / (name + ".java")).read_text(encoding="utf-8")
                body = declaration(text, "public class " + name)
                (work / (name + ".java")).write_text(
                    "import java.util.*;\nimport java.util.function.*;\n" + body, encoding="utf-8")
            test = work / "FogContextLifecycleTest.java"
            test.write_text((ROOT / "tools/tests/FogContextLifecycleTest.java").read_text(encoding="utf-8"), encoding="utf-8")
            compilation = subprocess.run(
                [javac, "-encoding", "UTF-8", "--release", "21", "-d", str(work),
                 *map(str, work.glob("*.java"))],
                capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(compilation.returncode, 0, compilation.stdout + compilation.stderr)
            execution = subprocess.run(
                [java, "-ea", "-cp", str(work), "FogContextLifecycleTest"],
                capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(execution.returncode, 0, execution.stdout + execution.stderr)
            print(execution.stdout.strip())


if __name__ == "__main__":
    unittest.main()

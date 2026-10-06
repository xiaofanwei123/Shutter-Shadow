"""Compare complete production traversal order with the saved upstream implementation."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from test_remote_view_area_resources import declaration


ROOT = Path(__file__).resolve().parents[1]
BEFORE = ROOT / "tools/fixtures/queries-traversal-before.zip"
SOURCE = ROOT / "src/main/java/com/xfw/shuttershadow/core/render/VisibleSectionDiscovery.java"
TRAVERSE = "src/main/java/com/xfw/shuttershadow/core/chunk_loading/BlockTraverse.java"


class VisibleLayerTraversalTests(unittest.TestCase):
    def test_complete_coordinate_sequence(self):
        fallback = Path("C:/Program Files/Zulu/zulu-21/bin")
        java = str(fallback / "java.exe") if fallback.is_dir() else shutil.which("java")
        javac = str(fallback / "javac.exe") if fallback.is_dir() else shutil.which("javac")
        self.assertTrue(java and javac, "Java 21 is required")
        with zipfile.ZipFile(BEFORE) as snapshot:
            original = snapshot.read(TRAVERSE).decode("utf-8")
        production = SOURCE.read_text(encoding="utf-8")
        text = """
            import java.util.ArrayList;
            import java.util.List;
            public class VisibleLayerTraversalTest {
                record SectionPos(int x, int z) {}
                record Visit(int x, int y, int z, boolean skipFrustumTest) {}
                static class Original {
        """ + declaration(original, "public interface BiIntFunc") \
            + declaration(original, "public static <T> T searchOnPlane(") + """
                }
                static class Current {
                    static SectionPos cameraSectionPos;
                    static int viewDistance;
                    static final List<Visit> visits = new ArrayList<>();
                    static void checkSection(int x, int y, int z, boolean skipFrustumTest) {
                        visits.add(new Visit(x, y, z, skipFrustumTest));
                    }
        """ + declaration(production, "private static void discoverBottomOrTopLayerVisibleChunks(") + """
                }
                public static void main(String[] args) {
                    int[][] centers = {{0,0}, {-1,-1}, {-65,37}, {127,-240}, {-1000000,1000000}};
                    int[] distances = {-2,0,1,2,3,4,8,16,32,64};
                    int[] heights = {-128,-4,0,5,16,319};
                    int scenarios = 0, coordinates = 0;
                    for (int[] center : centers) for (int distance : distances) for (int y : heights) {
                        List<Visit> expected = new ArrayList<>();
                        Original.<Object>searchOnPlane(center[0], center[1], distance - 1, (x,z) -> {
                            expected.add(new Visit(x,y,z,false)); return null;
                        });
                        Current.cameraSectionPos = new SectionPos(center[0],center[1]);
                        Current.viewDistance = distance;
                        Current.visits.clear();
                        Current.discoverBottomOrTopLayerVisibleChunks(y);
                        if (!expected.equals(Current.visits)) throw new AssertionError(
                            "changed complete sequence at " + center[0] + "," + y + "," + center[1] + ", distance=" + distance);
                        int lastLayer = Math.max(0,distance - 2);
                        int expectedCount = (lastLayer * 2 + 1) * (lastLayer * 2 + 1);
                        if (Current.visits.size() != expectedCount) throw new AssertionError("exclusive range changed");
                        if (!Current.visits.getFirst().equals(new Visit(center[0],y,center[1],false)))
                            throw new AssertionError("center must remain first even for range <= 1");
                        scenarios++;
                        coordinates += expected.size();
                    }
                    System.out.println("Visible layer traversal: " + scenarios + " scenarios, " + coordinates
                        + " coordinates, " + scenarios * 3 + " assertions passed.");
                }
            }
        """
        with tempfile.TemporaryDirectory(prefix="shuttershadow-visible-layer-test-") as folder:
            work = Path(folder)
            source = work / "VisibleLayerTraversalTest.java"
            source.write_text(text, encoding="utf-8")
            result = subprocess.run([javac, "-encoding", "UTF-8", "-d", str(work), str(source)],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([java, "-cp", str(work), "VisibleLayerTraversalTest"],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()

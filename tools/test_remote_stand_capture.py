"""Run production manual capture against a controlled renderer; never launch the game."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CAPTURE = "io.github.mortuusars.exposure.client.capture"
ACTION = CAPTURE + ".action"
CLIENT = "com.xfw.shuttershadow.client"
FIXTURES = {
    "com.xfw.shuttershadow.network.RemoteSceneStartS2C": "public record RemoteSceneStartS2C(long sequence) {}",
    "com.xfw.shuttershadow.network.CameraSessionCloseC2S": "public record CameraSessionCloseC2S(long sequence, boolean captured) {}",
    "com.xfw.shuttershadow.Shuttershadow": "public class Shuttershadow { public static final Logger LOGGER = new Logger(); public static class Logger { public void error(String text, Object... args) {} } }",
    "io.github.mortuusars.exposure.util.TranslatableError": "public class TranslatableError {}",
    "io.github.mortuusars.exposure.util.cycles.task.Result": """public record Result<T>(T value, boolean isSuccessful) {
        public static <T> Result<T> success(T value) { return new Result<>(value, true); }
        public static <T> Result<T> error(io.github.mortuusars.exposure.util.TranslatableError error) { return new Result<>(null, false); }
        public T unwrap() { return value; }
    }""",
    "io.github.mortuusars.exposure.client.image.Image": "public class Image {}",
    "io.github.mortuusars.exposure.world.entity.CameraStandEntity": "public class CameraStandEntity { public float getEyeHeight() { return 1.25F; } }",
    "net.minecraft.client.multiplayer.ClientPacketListener": "public class ClientPacketListener {}",
    "com.xfw.shuttershadow.access.IECamera": "public interface IECamera { void ip_setCameraY(float current, float old); }",
    "com.xfw.shuttershadow.access.IEGameRenderer": "public interface IEGameRenderer { void ip_setCamera(net.minecraft.client.Camera camera); }",
    "net.minecraft.client.Camera": "public class Camera implements com.xfw.shuttershadow.access.IECamera { public float eye; public void ip_setCameraY(float current, float old) { eye = current; } }",
    "net.minecraft.client.Minecraft": """public class Minecraft {
        private static final Minecraft INSTANCE = new Minecraft();
        public Object player = new Object(), cameraEntity = new Object(), hitResult = new Object(), crosshairPickEntity = new Object();
        public net.minecraft.client.multiplayer.ClientPacketListener connection = new net.minecraft.client.multiplayer.ClientPacketListener();
        public final Renderer gameRenderer = new Renderer(); public final Options options = new Options();
        public static Minecraft getInstance() { return INSTANCE; }
        public net.minecraft.client.multiplayer.ClientPacketListener getConnection() { return connection; }
        public Object getCameraEntity() { return cameraEntity; } public void execute(Runnable work) { work.run(); }
        public static class Renderer implements com.xfw.shuttershadow.access.IEGameRenderer {
            private Camera camera = new Camera(); public Camera getMainCamera() { return camera; } public void ip_setCamera(Camera value) { camera = value; }
        }
        public static class Options {
            private Object type = new Object(); public Object getCameraType() { return type; } public void setCameraType(Object value) { type = value; }
        }
    }""",
    "net.neoforged.neoforge.network.PacketDistributor": """public class PacketDistributor {
        public static final java.util.List<com.xfw.shuttershadow.network.CameraSessionCloseC2S> packets = new java.util.ArrayList<>();
        public static void sendToServer(com.xfw.shuttershadow.network.CameraSessionCloseC2S packet) { packets.add(packet); }
    }""",
    CLIENT + ".ImmersiveCameraClient": """public class ImmersiveCameraClient {
        public static boolean rendering; public static int begins, ends;
        public static void beginScreenshot(com.xfw.shuttershadow.network.RemoteSceneStartS2C scene, io.github.mortuusars.exposure.world.entity.CameraStandEntity stand) { rendering = true; begins++; }
        public static void endScreenshot(long sequence) { rendering = false; ends++; }
    }""",
    ACTION + ".CaptureAction": """public interface CaptureAction {
        default int requiredDelayTicks() { return 0; } default void initialize() {} default void delayTick(int ticks) {}
        default void beforeCapture() {} default void afterCapture() {}
    }""",
    ACTION + ".CompositeAction": """public class CompositeAction implements CaptureAction {
        private final CaptureAction[] actions; public CompositeAction(CaptureAction[] actions) { this.actions = actions; }
        public int requiredDelayTicks() { return java.util.Arrays.stream(actions).mapToInt(CaptureAction::requiredDelayTicks).max().orElse(0); }
        public void initialize() { for (var action : actions) action.initialize(); }
        public void delayTick(int ticks) { for (var action : actions) action.delayTick(ticks); }
        public void beforeCapture() { for (var action : actions) action.beforeCapture(); }
        public void afterCapture() { for (var action : actions) action.afterCapture(); }
    }""",
    ACTION + ".HideGuiAction": "public class HideGuiAction implements CaptureAction { public static int calls; public void beforeCapture() { calls++; } }",
    ACTION + ".SetCameraEntityAction": "public class SetCameraEntityAction implements CaptureAction { public static int calls; public void beforeCapture() { calls++; } }",
    CAPTURE + ".task.BackgroundScreenshotCaptureTask": """public class BackgroundScreenshotCaptureTask {
        public static int frames; public static Runnable render = () -> {}; public static boolean fail;
        public java.util.concurrent.CompletableFuture<io.github.mortuusars.exposure.util.cycles.task.Result<io.github.mortuusars.exposure.client.image.Image>> execute() {
            frames++; render.run();
            if (fail) throw new IllegalStateException("renderer failure");
            return java.util.concurrent.CompletableFuture.completedFuture(io.github.mortuusars.exposure.util.cycles.task.Result.success(new io.github.mortuusars.exposure.client.image.Image()));
        }
        public void tick() {}
    }""",
    CAPTURE + ".Capture": """public class Capture<T> {
        public static final io.github.mortuusars.exposure.util.TranslatableError ERROR_FAILED_GENERIC = new io.github.mortuusars.exposure.util.TranslatableError();
        protected final io.github.mortuusars.exposure.client.capture.task.BackgroundScreenshotCaptureTask capturingTask;
        protected final java.util.concurrent.CompletableFuture<io.github.mortuusars.exposure.util.cycles.task.Result<T>> completableFuture = new java.util.concurrent.CompletableFuture<>();
        protected final Timer timer; private boolean started, done;
        public Capture(io.github.mortuusars.exposure.client.capture.task.BackgroundScreenshotCaptureTask task, action.CaptureAction actions) { capturingTask = task; timer = new Timer(actions); }
        public java.util.concurrent.CompletableFuture<io.github.mortuusars.exposure.util.cycles.task.Result<T>> execute() { if (!started) { setStarted(); timer.start(); } return completableFuture; }
        public boolean isStarted() { return started; } public void setStarted() { started = true; }
        public boolean isDone() { return done; } public void setDone() { done = true; }
        public void tick() { capturingTask.tick(); timer.tick(); }
        public static class Timer {
            private final action.CaptureAction actions; private Runnable callback; private int remaining, ticks; private boolean running;
            public Timer(action.CaptureAction actions) { this.actions = actions; }
            public void whenEnded(Runnable callback) { this.callback = callback; }
            public void start() { actions.initialize(); remaining = actions.requiredDelayTicks(); running = true; }
            public void pause() { running = false; }
            public void tick() { if (!running) return; actions.delayTick(++ticks); if (--remaining <= 0) { running = false; callback.run(); } }
        }
    }""".replace("action.CaptureAction", ACTION + ".CaptureAction"),
}


class RemoteStandCaptureTests(unittest.TestCase):
    def test_production_capture(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        self.assertIsNotNone(javac, "JDK 21 javac is required")
        self.assertIsNotNone(java, "JDK 21 java is required")
        with tempfile.TemporaryDirectory(prefix="shuttershadow-manual-capture-") as temporary:
            folder = Path(temporary)
            sources = []
            for name, body in FIXTURES.items():
                path = folder / (name.replace(".", "/") + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf-8")
                sources.append(str(path))
            sources.extend(str(ROOT / path) for path in (
                "src/main/java/com/xfw/shuttershadow/client/RemoteStandCapture.java",
                "tools/tests/RemoteStandCaptureTest.java"))
            result = subprocess.run([javac, "-encoding", "UTF-8", "--release", "21", "-d", str(folder), *sources],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for scenario in ("single-frame", "exposure-delay", "cancel", "disconnect", "render-failure", "before-failure", "after-failure"):
                with self.subTest(scenario=scenario):
                    result = subprocess.run([java, "-ea", "-cp", str(folder), "RemoteStandCaptureTest", scenario],
                                            capture_output=True, text=True, encoding="utf-8", errors="replace")
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    print(result.stdout.strip(), flush=True)


if __name__ == "__main__":
    unittest.main()

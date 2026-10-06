import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import com.xfw.shuttershadow.client.RemoteStandCapture;
import com.xfw.shuttershadow.network.RemoteSceneStartS2C;
import io.github.mortuusars.exposure.client.capture.action.CaptureAction;
import io.github.mortuusars.exposure.client.capture.action.HideGuiAction;
import io.github.mortuusars.exposure.client.capture.action.SetCameraEntityAction;
import io.github.mortuusars.exposure.client.capture.task.BackgroundScreenshotCaptureTask;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;

/** Executes production capture with unavailable terrain APIs and a controlled native screenshot. */
public final class RemoteStandCaptureTest {
    private static int checks, before, after, initialized, delayed;

    public static void main(String[] args) {
        String scenario = args[0];
        Minecraft mc = Minecraft.getInstance();
        Object entity = mc.getCameraEntity(), type = mc.options.getCameraType();
        Object hit = mc.hitResult, pick = mc.crosshairPickEntity;
        var camera = mc.gameRenderer.getMainCamera();
        var stand = new CameraStandEntity();
        var actions = new CaptureAction() {
            public int requiredDelayTicks() { return scenario.equals("exposure-delay") ? 3 : 0; }
            public void initialize() { initialized++; }
            public void delayTick(int ticks) { delayed++; }
            public void beforeCapture() {
                before++;
                mc.options.setCameraType(new Object()); mc.hitResult = new Object(); mc.crosshairPickEntity = new Object();
                if (scenario.equals("before-failure")) throw new IllegalStateException("before capture");
            }
            public void afterCapture() { after++; if (scenario.equals("after-failure")) throw new IllegalStateException("after capture"); }
        };
        BackgroundScreenshotCaptureTask.render = () -> {
            check(RemoteStandCapture.isRenderingScreenshot() && ImmersiveCameraClient.rendering, "remote scope is active only inside native drawing");
            check(mc.getCameraEntity() == stand && mc.gameRenderer.getMainCamera() != camera,
                    "one-shot drawing uses the real stand and a temporary camera");
            check(mc.gameRenderer.getMainCamera().eye == stand.getEyeHeight(), "stand eye offset is retained");
        };
        BackgroundScreenshotCaptureTask.fail = scenario.equals("render-failure");
        var capture = new RemoteStandCapture(new RemoteSceneStartS2C(-1), stand,
                new CaptureAction[]{new HideGuiAction(), new SetCameraEntityAction(), actions});
        var future = capture.execute();
        check(initialized == 1, "Exposure action initialization still runs once");
        if (scenario.equals("exposure-delay")) {
            capture.tick(); capture.tick();
            check(!future.isDone() && BackgroundScreenshotCaptureTask.frames == 0, "Exposure's own three-tick delay is preserved");
            capture.tick();
        } else if (scenario.equals("cancel")) {
            RemoteStandCapture.cancel(-1); capture.tick();
        } else {
            if (scenario.equals("disconnect")) mc.connection = new ClientPacketListener();
            capture.tick();
        }
        check(future.isDone(), "capture finishes without waiting for terrain or warming another frame");
        boolean success = scenario.equals("single-frame") || scenario.equals("exposure-delay");
        check(future.join().isSuccessful() == success, "success and failure preserve completion results");
        int expectedFrames = scenario.equals("cancel") || scenario.equals("disconnect") || scenario.equals("before-failure") ? 0 : 1;
        check(BackgroundScreenshotCaptureTask.frames == expectedFrames, "native screenshot executes at most once");
        int expectedActions = scenario.equals("cancel") || scenario.equals("disconnect") ? 0 : 1;
        check(before == expectedActions && after == expectedActions, "drawing actions restore once including failures");
        check(!ImmersiveCameraClient.rendering && !RemoteStandCapture.isRenderingScreenshot(), "render scope always clears");
        check(mc.getCameraEntity() == entity && mc.gameRenderer.getMainCamera() == camera && mc.options.getCameraType() == type,
                "camera entity, original camera and perspective restore");
        check(mc.hitResult == hit && mc.crosshairPickEntity == pick, "picking state restores");
        check(HideGuiAction.calls == 0 && SetCameraEntityAction.calls == 0, "offscreen capture never changes persistent GUI or camera actions");
        int expectedPackets = scenario.equals("disconnect") ? 0 : 1;
        check(PacketDistributor.packets.size() == expectedPackets, "completion is reported only to the original connection");
        if (expectedPackets == 1) check(PacketDistributor.packets.getFirst().captured() == success, "completion packet reports the actual image outcome");
        for (int i = 0; i < 20; i++) capture.tick();
        check(BackgroundScreenshotCaptureTask.frames == expectedFrames && PacketDistributor.packets.size() == expectedPackets,
                "finished capture cannot draw or acknowledge twice");
        System.out.println("PASS: " + scenario + " (" + checks + " checks)");
    }

    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
        checks++;
    }
}

import java.util.*;

/** Production ownership/release decisions with the real 1.21.1 texture lifecycle. */
public class LightmapLifecycleTest {
    private static int assertions;
    private static final Minecraft CLIENT = Minecraft.getInstance();

    public static void main(String[] args) {
        vanillaCloseLeavesRegistration();
        retainActiveLightmap();
        releaseSecondaryExactlyOnce();
        transferOwnershipAfterSeamlessTravel();
        repeatedHelperRefresh();
        cleanupPartlyBuiltHelperSet();
        closeManagerAfterSecondaryCleanup();
        System.out.println("Lightmap lifecycle: 7 scenarios / " + assertions + " assertions passed");
    }

    private static DimensionRenderHelper reset() {
        ClientWorldLoader.RENDER_HELPER_MAP.clear();
        CLIENT.textures.close();
        CLIENT.level = new Level(0);
        CLIENT.gameRenderer.current = new LightTexture(CLIENT.gameRenderer, CLIENT);
        return new DimensionRenderHelper(CLIENT.level);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static DimensionRenderHelper secondary(int id) {
        DimensionRenderHelper helper = new DimensionRenderHelper(new Level(id));
        ClientWorldLoader.RENDER_HELPER_MAP.put(id, helper);
        return helper;
    }

    private static void vanillaCloseLeavesRegistration() {
        reset();
        DimensionRenderHelper remote = secondary(1);
        remote.lightmapTexture.close();
        check(CLIENT.textures.byPath.containsKey(remote.lightmapTexture.lightTextureLocation),
                "original close reproduces a stale registry entry");
        check(remote.lightmapTexture.lightPixels.closes == 1, "original close frees the pixels only once");
    }

    private static void retainActiveLightmap() {
        DimensionRenderHelper primary = reset();
        primary.cleanUp();
        primary.cleanUp();
        check(primary.lightmapTexture == CLIENT.gameRenderer.current, "current lightmap remains shared");
        check(primary.lightmapTexture.lightPixels.closes == 0, "shared main lightmap must stay open");
        check(CLIENT.textures.byPath.get(primary.lightmapTexture.lightTextureLocation)
                == primary.lightmapTexture.lightTexture, "main registration remains available");
    }

    private static void releaseSecondaryExactlyOnce() {
        reset();
        DimensionRenderHelper remote = secondary(1);
        int id = remote.lightmapTexture.lightTexture.getId();
        remote.cleanUp();
        remote.cleanUp();
        check(!CLIENT.textures.byPath.containsKey(remote.lightmapTexture.lightTextureLocation),
                "secondary cleanup must unregister the dynamic location");
        check(remote.lightmapTexture.lightPixels.closes == 1, "cleanup must close native pixels once");
        check(TextureUtil.releases.getOrDefault(id, 0) == 1, "manager safeClose must not free the GL id twice");
        check(CLIENT.textures.byPath.size() == 1, "cleanup retains only the primary lightmap");
    }

    private static void transferOwnershipAfterSeamlessTravel() {
        DimensionRenderHelper source = reset();
        ClientWorldLoader.RENDER_HELPER_MAP.put(0, source);
        DimensionRenderHelper target = secondary(1);
        DimensionRenderHelper other = secondary(2);
        CLIENT.level = target.world;
        CLIENT.gameRenderer.current = target.lightmapTexture;
        ClientWorldLoader.disposeRenderHelpers();
        check(source.lightmapTexture.lightPixels.closes == 1, "former main texture can be released after ownership changes");
        check(other.lightmapTexture.lightPixels.closes == 1, "unused remote texture is released");
        check(target.lightmapTexture.lightPixels.closes == 0, "remote texture adopted by seamless travel must remain open");
        check(CLIENT.textures.byPath.size() == 1
                && CLIENT.textures.byPath.containsKey(target.lightmapTexture.lightTextureLocation),
                "registry must retain the currently owned texture only");
        check(ClientWorldLoader.RENDER_HELPER_MAP.isEmpty(), "cleanup removes obsolete helper references");
        DimensionRenderHelper rebuilt = new DimensionRenderHelper(CLIENT.level);
        check(rebuilt.lightmapTexture == target.lightmapTexture, "rebuilt active helper reuses transferred texture");
    }

    private static void repeatedHelperRefresh() {
        DimensionRenderHelper active = reset();
        for (int round = 0; round < 100; round++) {
            secondary(1); secondary(2);
            ClientWorldLoader.disposeRenderHelpers();
            check(CLIENT.textures.byPath.size() == 1, "refresh must not accumulate closed registrations");
            check(active.lightmapTexture.lightPixels.closes == 0, "refresh never closes the active texture");
        }
    }

    private static void cleanupPartlyBuiltHelperSet() {
        reset();
        DimensionRenderHelper first = secondary(1);
        try {
            // A later failure does not erase ownership of already returned helpers.
            throw new IllegalStateException("later initialization failure");
        } catch (IllegalStateException expected) {
            ClientWorldLoader.disposeRenderHelpers();
        }
        check(first.lightmapTexture.lightPixels.closes == 1, "already registered helper releases on failed outer initialization");
        check(CLIENT.textures.byPath.size() == 1, "failed outer initialization leaves no secondary registration");
    }

    private static void closeManagerAfterSecondaryCleanup() {
        DimensionRenderHelper primary = reset();
        DimensionRenderHelper remote = secondary(1);
        remote.cleanUp();
        CLIENT.textures.close();
        check(remote.lightmapTexture.lightPixels.closes == 1, "manager shutdown must not revisit disposed secondary texture");
        check(primary.lightmapTexture.lightPixels.closes == 1, "manager shutdown still owns current texture final release");
        check(CLIENT.textures.byPath.isEmpty(), "manager shutdown clears all entries");
    }
}

record ResourceLocation(String name) {
    static ResourceLocation withDefaultNamespace(String name) { return new ResourceLocation(name); }
}
record Level(int id) {
    Level dimension() { return this; }
    String location() { return "test:" + id; }
}
final class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();
    final TextureManager textures = new TextureManager();
    final GameRenderer gameRenderer = new GameRenderer();
    Level level;
    static Minecraft getInstance() { return INSTANCE; }
    TextureManager getTextureManager() { return textures; }
}
final class GameRenderer {
    LightTexture current;
    LightTexture lightTexture() { return current; }
}
final class NativeImage {
    int closes;
    final int width, height;
    NativeImage(int width, int height, boolean clear) { this.width = width; this.height = height; }
    int getWidth() { return width; }
    int getHeight() { return height; }
    void setPixelRGBA(int x, int y, int rgba) {}
    void close() { closes++; }
}
final class TextureUtil {
    static int nextId;
    static final Map<Integer, Integer> releases = new HashMap<>();
    static int generateTextureId() { return ++nextId; }
    static void prepareImage(int id, int width, int height) {}
    static void releaseTextureId(int id) { releases.merge(id, 1, Integer::sum); }
}
final class RenderSystem {
    static boolean isOnRenderThread() { return true; }
    static void recordRenderCall(Runnable task) { task.run(); }
}
interface Tickable {}
final class MissingTextureAtlasSprite {
    private static final AbstractTexture MISSING = new AbstractTexture();
    static AbstractTexture getTexture() { return MISSING; }
}
final class Helper { static void log(String message) {} }
final class TestLogger { void warn(String message, Object... details) {} }

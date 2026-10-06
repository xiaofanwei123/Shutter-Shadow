import com.xfw.shuttershadow.core.CoreSettings;
import com.xfw.shuttershadow.core.render.RemoteViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;

import java.util.List;

/** 执行源文件中原样提取的所有权、预设与过期清理方法；只替换区段的 GL 资源。 */
public final class RemoteViewAreaResourceTest {
    private static int checks;
    private static int scenarios;

    public static void main(String[] args) {
        rawOnly();
        sharedPresets();
        purgeBeforeTotalRelease();
        totalReleaseBeforePendingPurge();
        batchedPurge();
        sharedPresetsAfterPurge();
        System.out.println("PASS: remote view section ownership: " + scenarios + " scenarios, " + checks + " assertions");
    }

    private static RemoteViewArea area(int x, int y, int z) {
        check(CoreSettings.PRE_GAME_RENDER_TASK_LIST.pending.isEmpty(), "previous purge must be drained");
        SectionRenderDispatcher.CREATED.clear();
        scenarios++;
        return new RemoteViewArea(x, y, z);
    }

    private static List<RenderSection> created() {
        return List.copyOf(SectionRenderDispatcher.CREATED);
    }

    private static void releases(List<RenderSection> sections, int count, String reason) {
        for (RenderSection section : sections) check(section.releases == count,
                reason + " at " + section.x + "," + section.y + "," + section.z + ": " + section.releases);
    }

    private static void rawOnly() {
        RemoteViewArea area = area(3, 2, 3);
        check(area.rawFetch(100, -1, 100, System.nanoTime()) == null, "outside world height must allocate no sections");
        area.rawFetch(100, 0, 100, System.nanoTime());
        check(area.columnsForTest() == 1 && area.presetsForTest() == 0, "rawFetch column must exist independently of any preset");
        List<RenderSection> sections = created();
        check(sections.size() == 2, "one column owns every vertical section");
        area.releaseAllBuffers();
        releases(sections, 1, "raw-only column must be released");
        check(area.columnsForTest() == 0 && area.presetsForTest() == 0 && !area.aliveForTest(), "released area must clear owners and become inactive");
        area.releaseAllBuffers();
        releases(sections, 1, "repeating total release must not close again");
    }

    private static void sharedPresets() {
        RemoteViewArea area = area(3, 2, 3);
        area.repositionCamera(0, 0);
        area.repositionCamera(16, 0);
        area.rawFetch(100, 0, 100, System.nanoTime());
        check(area.presetsForTest() == 2 && area.columnsForTest() == 13, "neighboring presets share six of their nine columns plus independent remote column");
        List<RenderSection> sections = created();
        check(sections.size() == 26, "shared preset references must not duplicate section ownership");
        area.releaseAllBuffers();
        releases(sections, 1, "every shared and independent column must close exactly once");
        area.releaseAllBuffers();
        releases(sections, 1, "stale current preset references must not trigger double close");
    }

    private static void purgeBeforeTotalRelease() {
        RemoteViewArea area = area(3, 2, 3);
        area.rawFetch(100, 0, 100, 0);
        List<RenderSection> expired = created();
        area.rawFetch(101, 0, 100, System.nanoTime());
        List<RenderSection> live = created().subList(2, 4);
        area.purgeForTest();
        check(area.columnsForTest() == 1, "purge must remove only expired owner");
        releases(expired, 0, "purge release is deferred to render task");
        CoreSettings.PRE_GAME_RENDER_TASK_LIST.drain();
        releases(expired, 1, "expired column render task must close its sections");
        area.releaseAllBuffers();
        releases(expired, 1, "total release must not revisit purged columns");
        releases(live, 1, "total release must still close retained columns");
    }

    private static void totalReleaseBeforePendingPurge() {
        RemoteViewArea area = area(3, 2, 3);
        area.rawFetch(100, 0, 100, 0);
        List<RenderSection> expired = created();
        area.rawFetch(101, 0, 100, System.nanoTime());
        List<RenderSection> live = created().subList(2, 4);
        area.purgeForTest();
        area.releaseAllBuffers();
        releases(live, 1, "total release closes live owners while purge is pending");
        releases(expired, 0, "pending expired sections stay owned by their release task");
        CoreSettings.PRE_GAME_RENDER_TASK_LIST.drain();
        releases(expired, 1, "pending purge must complete after total release");
        releases(live, 1, "pending purge must not close unrelated former owners");
        area.releaseAllBuffers();
        releases(created(), 1, "all final resources must close exactly once");
    }

    private static void batchedPurge() {
        RemoteViewArea area = area(1, 101, 1);
        area.rawFetch(100, 0, 100, 0);
        List<RenderSection> sections = created();
        area.purgeForTest();
        CoreSettings.PRE_GAME_RENDER_TASK_LIST.tick();
        check(sections.stream().filter(section -> section.releases == 1).count() == 100, "purge must respect 100-section render work budget");
        check(CoreSettings.PRE_GAME_RENDER_TASK_LIST.pending.size() == 1, "remaining resources must keep release task alive");
        area.releaseAllBuffers();
        CoreSettings.PRE_GAME_RENDER_TASK_LIST.drain();
        releases(sections, 1, "purge tail must not be lost when total release intervenes");
    }

    private static void sharedPresetsAfterPurge() {
        RemoteViewArea area = area(3, 2, 3);
        area.repositionCamera(0, 0);
        area.repositionCamera(16, 0);
        List<RenderSection> sections = created();
        area.rawFetch(100, 0, 100, 0);
        List<RenderSection> expired = created().subList(sections.size(), sections.size() + 2);
        area.purgeForTest();
        check(area.columnsForTest() == 12, "active presets retain shared columns while unrelated remote column expires");
        CoreSettings.PRE_GAME_RENDER_TASK_LIST.drain();
        releases(sections, 0, "preset refresh must protect all retained owner columns");
        area.releaseAllBuffers();
        releases(sections, 1, "all retained preset columns close once during disposal");
        releases(expired, 1, "expired independent column stays released once");
    }

    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
}

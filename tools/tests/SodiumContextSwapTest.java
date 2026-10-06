import com.xfw.shuttershadow.compat.SodiumRenderingContext;
import com.xfw.shuttershadow.mixin.compat.sodium.MixinSodiumRenderSectionManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.OcclusionSectionCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;

import java.lang.reflect.Field;
import java.util.List;

/** 直接执行生产 Mixin 的交换方法，验证第二次交换能恢复所有视图字段。 */
public class SodiumContextSwapTest {
    public static void main(String[] args) throws Exception {
        var manager = new MixinSodiumRenderSectionManager();
        var source = new SodiumRenderingContext(8);
        var camera = new SodiumRenderingContext(32);
        var constructor = SortedRenderLists.class.getDeclaredConstructor(ObjectArrayList.class);
        constructor.setAccessible(true);
        source.renderLists = constructor.newInstance(new ObjectArrayList<>());
        source.sectionCollector = new OcclusionSectionCollector(5, TaskQueueType.INITIAL_BUILD, TaskQueueType.ALWAYS_DEFER);
        source.lastSectionCollector = new OcclusionSectionCollector(4, TaskQueueType.INITIAL_BUILD, TaskQueueType.ALWAYS_DEFER);
        var names = List.of("renderDistance", "renderLists", "sectionCollector", "lastSectionCollector", "taskLists");
        for (String name : names) {
            field(name).set(manager, SodiumRenderingContext.class.getField(name).get(source));
        }
        var originalCameraLists = camera.renderLists;
        var originalCameraTasks = camera.taskLists;
        manager.ip_swapContext(camera);
        check(field("renderDistance").getInt(manager) == 32, "camera distance");
        check(field("renderLists").get(manager) == originalCameraLists, "camera render lists");
        check(field("taskLists").get(manager) == originalCameraTasks, "camera task queues");
        check(field("sectionCollector").get(manager) == null, "camera has no source collector");
        check(field("lastSectionCollector").get(manager) == null, "camera has no source upload collector");
        var renderedCollector = new OcclusionSectionCollector(6, TaskQueueType.INITIAL_BUILD, TaskQueueType.ALWAYS_DEFER);
        field("lastSectionCollector").set(manager, renderedCollector);
        manager.ip_swapContext(camera);
        for (String name : names) {
            Object actual = field(name).get(manager);
            Object expected = SodiumRenderingContext.class.getField(name).get(source);
            check(name.equals("renderDistance") ? actual.equals(expected) : actual == expected, "restore " + name);
        }
        check(camera.lastSectionCollector == renderedCollector, "camera retains its own upload collector");
        System.out.println("Sodium context swap: 11 checks passed");
    }

    private static Field field(String name) throws Exception {
        Field field = MixinSodiumRenderSectionManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

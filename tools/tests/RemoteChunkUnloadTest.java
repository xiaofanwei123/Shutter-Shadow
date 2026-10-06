import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.core.PlatformBridge;
import com.xfw.shuttershadow.core.chunk_loading.RemoteClientChunkMap;
import com.xfw.shuttershadow.core.render.RemoteViewArea;
import fixture.Trace;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class RemoteChunkUnloadTest {
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static RemoteViewArea area(ChunkPos position) {
        RemoteViewArea area = new RemoteViewArea();
        area.columnMap.put(position.toLong(), new RemoteViewArea.Column(
                new RenderSection[]{new RenderSection(), new RenderSection(), new RenderSection()}));
        return area;
    }

    private static void put(RemoteClientChunkMap chunks, LevelChunk chunk) {
        chunks.modifyChunkMap(map -> map.put(chunk.getPos().toLong(), chunk));
    }

    public static void main(String[] args) throws InterruptedException {
        Level world = new Level("target");
        Level otherWorld = new Level("other");
        ChunkPos position = new ChunkPos(-7, 11);
        ChunkPos neighboringPosition = new ChunkPos(-6, 11);
        LevelChunk chunk = new LevelChunk(world, position);
        RemoteClientChunkMap chunks = new RemoteClientChunkMap(world);
        RemoteViewArea target = area(position);
        RemoteViewArea other = area(position);
        target.columnMap.put(neighboringPosition.toLong(), new RemoteViewArea.Column(
                new RenderSection[]{new RenderSection()}));
        var column = target.columnMap.get(position.toLong());
        ClientWorldLoader.WORLD_RENDERER_MAP.put(world.dimension(), new LevelRenderer(target));
        ClientWorldLoader.WORLD_RENDERER_MAP.put(otherWorld.dimension(), new LevelRenderer(other));
        PlatformBridge.check = unloading -> {
            check(unloading == chunk, "notification preserves the removed chunk");
            check(!chunks.chunkMapForMainThread.containsKey(position.toLong()), "main map removed before event");
            check(!chunks.chunkMapForOtherThreads.containsKey(position.toLong()), "worker map removed before event");
        };
        put(chunks, chunk);
        chunks.drop(position);
        check(Trace.EVENTS.equals(List.of("platform", "world", "sodium", "mesh", "mesh", "mesh")),
                "platform, world, Sodium and section reset preserve the original order");
        check(target.columnMap.get(position.toLong()) == column, "unload resets the existing column without reallocating");
        for (RenderSection section : column.sections) check(section.resets == 1, "all height sections reset once");
        check(target.columnMap.get(neighboringPosition.toLong()).sections[0].resets == 0, "neighbor column remains live");
        for (RenderSection section : other.columnMap.get(position.toLong()).sections)
            check(section.resets == 0, "same coordinates in another dimension remain live");

        Trace.EVENTS.clear();
        chunks.drop(position);
        check(Trace.EVENTS.isEmpty(), "already missing chunk produces no repeated notification/reset");

        PlatformBridge.check = unloading -> {};
        ClientWorldLoader.WORLD_RENDERER_MAP.remove(world.dimension());
        put(chunks, chunk);
        chunks.drop(position);
        check(Trace.EVENTS.equals(List.of("platform", "world", "sodium")), "missing renderer still unloads safely");

        Trace.EVENTS.clear();
        ClientWorldLoader.WORLD_RENDERER_MAP.put(world.dimension(), new LevelRenderer(new ViewArea()));
        put(chunks, chunk);
        chunks.drop(position);
        check(Trace.EVENTS.equals(List.of("platform", "world", "sodium")), "ordinary ViewArea does not receive remote resets");

        Trace.EVENTS.clear();
        put(chunks, chunk);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { chunks.drop(position); }
            catch (Throwable failure) { workerFailure.set(failure); }
        });
        worker.start();
        worker.join();
        check(workerFailure.get() instanceof IllegalArgumentException, "worker thread cannot run unload callbacks");
        check(chunks.chunkMapForMainThread.get(position.toLong()) == chunk, "rejected worker unload preserves main data");
        check(chunks.chunkMapForOtherThreads.get(position.toLong()) == chunk, "rejected worker unload preserves worker data");
        check(Trace.EVENTS.isEmpty(), "rejected worker unload produces no callbacks");

        System.out.println("Remote chunk unload: 5 scenarios, " + assertions + " assertions passed.");
    }
}

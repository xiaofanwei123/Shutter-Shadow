import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BooleanSupplier;

public class ShutdownChunkUnloadTest {
    private static int assertions;

    @FunctionalInterface
    interface Operation<T> { T call(Object... arguments); }

    static class Server {
        boolean running;
        boolean isRunning() { return running; }
    }

    static class Level {
        final Server server = new Server();
        Server getServer() { return server; }
    }

    static class Harness {
        final Level level = new Level();
        final Queue<Runnable> unloadQueue = new ArrayDeque<>();
        final Queue<Runnable> generationTasks = new ArrayDeque<>();
        final List<String> saved = new ArrayList<>();
        final List<String> processed = new ArrayList<>();
        int callbacks;
        int extraWork;

        // PRODUCTION_HOOKS

        @SuppressWarnings("unchecked")
        void processUnloads(BooleanSupplier hasTime) {
            shuttershadow$yieldShutdownUnloads(hasTime, arguments -> {
                if (arguments[0] != hasTime) throw new AssertionError("native time budget replaced");
                int excess = Math.max(0, unloadQueue.size() - 2000);
                Runnable callback;
                while ((hasTime.getAsBoolean() || excess > 0)
                        && (callback = (Runnable) shuttershadow$pollShutdownUnload(unloadQueue,
                        original -> ((Queue<Runnable>) original[0]).poll())) != null) {
                    excess--;
                    if (++callbacks > 10000) throw new IllegalStateException("reproduced native busy loop");
                    callback.run();
                }
                return null;
            });
        }

        void shutdown() {
            int passes = 0;
            while (!unloadQueue.isEmpty()) {
                if (++passes > 10) throw new AssertionError("shutdown cannot drain");
                // stopServer -> ServerChunkCache.tick first advances generation.
                processUnloads(() -> true);
                // stopServer -> waitUntilNextTick pumps the original task executors.
                Runnable task;
                while ((task = generationTasks.poll()) != null) task.run();
            }
        }

        void schedule(String id, int[] generationReferences) {
            unloadQueue.add(() -> {
                processed.add(id);
                if (generationReferences[0] != 0) {
                    schedule(id, generationReferences);
                    return;
                }
                saved.add(id);
            });
        }
    }

    static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] arguments) {
        Harness nativeLoop = new Harness();
        nativeLoop.level.server.running = true;
        nativeLoop.schedule("waiting", new int[]{1});
        try {
            nativeLoop.processUnloads(() -> true);
            throw new AssertionError("expected native busy loop");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("busy loop"), "unbounded native loop reproduced");
        }

        Harness shutdown = new Harness();
        int[] references = {1};
        shutdown.schedule("ready", new int[]{0});
        shutdown.schedule("waiting", references);
        shutdown.generationTasks.add(() -> references[0]--);
        shutdown.processUnloads(() -> true);
        check(shutdown.callbacks == 2, "each original callback runs at most once in the shutdown pass");
        check(shutdown.saved.equals(List.of("ready")), "a not-ready chunk is never marked saved");
        check(shutdown.unloadQueue.size() == 1, "the retry remains queued without being dropped");
        check(references[0] == 1, "generation claims are not forcibly cleared");
        shutdown.shutdown();
        check(shutdown.saved.equals(List.of("ready", "waiting")), "task pumping allows every chunk to save");
        check(references[0] == 0 && shutdown.unloadQueue.isEmpty(), "normal claim release permits completion");
        check(shutdown.shuttershadow$shutdownUnloadBudget == -1, "shutdown budget restored after completion");

        Harness additions = new Harness();
        additions.unloadQueue.add(() -> {
            additions.processed.add("first");
            additions.unloadQueue.add(() -> additions.processed.add("new"));
        });
        additions.processUnloads(() -> true);
        check(additions.processed.equals(List.of("first")), "callbacks added during shutdown wait for another pass");
        check(additions.unloadQueue.size() == 1, "new callbacks are retained");
        additions.processUnloads(() -> true);
        check(additions.processed.equals(List.of("first", "new")), "retained callbacks execute on the next pass");

        Harness large = new Harness();
        int[] pending = {1};
        large.schedule("waiting", pending);
        for (int i = 0; i < 2500; i++) large.schedule("ready-" + i, new int[]{0});
        large.processUnloads(() -> true);
        check(large.callbacks == 2501 && large.saved.size() == 2500, "large backlogs drain exactly the initial budget");
        check(large.unloadQueue.size() == 1, "large-backlog retry also remains intact");
        large.generationTasks.add(() -> pending[0]--);
        large.shutdown();
        check(large.saved.size() == 2501, "large backlogs finish saving after claim release");

        Harness normal = new Harness();
        normal.level.server.running = true;
        normal.unloadQueue.add(() -> normal.unloadQueue.add(() -> normal.extraWork++));
        normal.processUnloads(() -> true);
        check(normal.callbacks == 2 && normal.extraWork == 1, "running worlds preserve native same-pass processing");
        normal.schedule("budgeted", new int[]{0});
        normal.processUnloads(() -> false);
        check(normal.unloadQueue.size() == 1, "running worlds preserve the original time budget");

        Harness empty = new Harness();
        empty.processUnloads(() -> true);
        check(empty.callbacks == 0 && empty.shuttershadow$shutdownUnloadBudget == -1, "empty passes safely restore state");
        empty.shuttershadow$yieldShutdownUnloads(() -> true, args -> {
            empty.unloadQueue.add(() -> empty.extraWork++);
            empty.processUnloads(() -> true);
            check(empty.shuttershadow$shutdownUnloadBudget == 0, "nested processing restores the enclosing budget");
            return null;
        });
        check(empty.shuttershadow$shutdownUnloadBudget == -1, "nested completion restores the initial budget");

        Harness exception = new Harness();
        exception.unloadQueue.add(() -> { throw new IllegalArgumentException("native failure"); });
        try {
            exception.processUnloads(() -> true);
            throw new AssertionError("native exception was swallowed");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().equals("native failure"), "native exceptions retain their semantics");
        }
        check(exception.shuttershadow$shutdownUnloadBudget == -1, "exceptional processing restores its budget");
        System.out.println("Shutdown unload behavior: " + assertions + " assertions passed");
    }
}

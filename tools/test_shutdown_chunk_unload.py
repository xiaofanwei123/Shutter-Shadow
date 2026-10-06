"""Exercise the production unload hooks without starting Minecraft or discarding saves."""

from pathlib import Path
import os
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
MIXIN = ROOT / "src/main/java/com/xfw/shuttershadow/mixin/minecraft/server/MixinChunkMap_C.java"
ARTIFACT = ROOT / "build/moddev/artifacts/neoforge-21.1.252.jar"


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    end = opening + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def javap(name):
    return subprocess.check_output(
        ["javap", "-classpath", str(ARTIFACT), "-p", "-c", name],
        text=True, encoding="utf-8",
    )


def bytecode_method(text, signature):
    start = text.index(signature)
    end = re.search(r"\n  (?:public|private|protected|static|boolean|void) ", text[start + 1 :])
    return text[start:] if end is None else text[start : start + 1 + end.start()]


def validate_native_scheduling():
    count = 0

    def check(value, message):
        nonlocal count
        count += 1
        if not value:
            raise AssertionError(message)

    chunks = javap("net.minecraft.server.level.ChunkMap")
    process = bytecode_method(chunks, "private void processUnloads(")
    check(process.count("java/util/Queue.poll:") == 1, "the hook matches one native unload poll")
    check("java/util/function/BooleanSupplier.getAsBoolean" in process, "native budget remains active")
    has_work = bytecode_method(chunks, "public boolean hasWork(")
    check("Field pendingUnloads:" in has_work and "java/util/Queue.isEmpty" in has_work,
          "pending callbacks keep the shutdown loop alive until saving finishes")
    schedule = bytecode_method(chunks, "private void scheduleUnload(")
    check("ChunkHolder.getSaveSyncFuture" in schedule, "unloads retain the save dependency")
    check("CompletableFuture.thenRunAsync" in schedule, "completed dependencies enqueue a callback")
    callback = bytecode_method(chunks, "private void lambda$scheduleUnload$12(")
    check(callback.index("ChunkHolder.isReadyForSaving") < callback.index("Method scheduleUnload:"),
          "not-ready callbacks requeue")
    check("Method save:" in callback, "the eventual callback still saves the chunk")
    holder = bytecode_method(javap("net.minecraft.server.level.ChunkHolder"), "public boolean isReadyForSaving(")
    check("getGenerationRefCount" in holder and "CompletableFuture.isDone" in holder,
          "saving waits for generation claims and save dependencies")
    server = javap("net.minecraft.server.MinecraftServer")
    stop = bytecode_method(server, "public void stopServer(")
    check(stop.index("ServerChunkCache.tick:") < stop.index("Method waitUntilNextTick:"),
          "each shutdown pass yields to the server task pump")
    check(stop.index("Method waitUntilNextTick:") < stop.index("Method saveAllChunks:"),
          "all chunks are saved after draining work")
    wait = bytecode_method(server, "protected void waitUntilNextTick(")
    check("Method runAllTasks:" in wait and "Method managedBlock:" in wait,
          "the task pump remains available between unload passes")
    poll = bytecode_method(server, "private boolean pollTaskInternal(")
    check("ServerChunkCache.pollTask:" in poll, "server task pumping reaches chunk executors")
    cache = javap("net.minecraft.server.level.ServerChunkCache")
    updates = bytecode_method(cache, "boolean runDistanceManagerUpdates(")
    check("ChunkMap.runGenerationTasks:" in updates, "each update advances generation tasks")
    executor = bytecode_method(javap("net.minecraft.server.level.ServerChunkCache$MainThreadExecutor"),
                               "public boolean pollTask(")
    check("ServerChunkCache.runDistanceManagerUpdates" in executor and "BlockableEventLoop.pollTask" in executor,
          "chunk executor pumping advances queued main-thread callbacks")
    task = bytecode_method(javap("net.minecraft.server.level.ChunkGenerationTask"),
                          "public java.util.concurrent.CompletableFuture<?> runUntilWait(")
    check("Method releaseClaim:" in task, "finished or cancelled generation tasks release claims")
    release = bytecode_method(chunks, "public void releaseGeneration(")
    check("GenerationChunkHolder.decreaseGenerationRefCount" in release,
          "generation completion lowers the real counter needed by isReadyForSaving")
    return count


def main():
    source = MIXIN.read_text(encoding="utf-8")
    production = "\n".join((
        re.search(r"@Unique\s+(private int shuttershadow\$shutdownUnloadBudget = -1;)", source).group(1),
        method(source, "private void shuttershadow$yieldShutdownUnloads("),
        method(source, "private Object shuttershadow$pollShutdownUnload("),
    ))
    fixture = (ROOT / "tools/tests/ShutdownChunkUnloadTest.java").read_text(encoding="utf-8")
    fixture = fixture.replace("// PRODUCTION_HOOKS", production)
    with tempfile.TemporaryDirectory(prefix="shuttershadow-shutdown-") as directory:
        temp = Path(directory)
        java_file = temp / "ShutdownChunkUnloadTest.java"
        java_file.write_text(fixture, encoding="utf-8")
        env = os.environ.copy()
        subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(temp), str(java_file)], check=True, env=env)
        subprocess.run(["java", "-cp", str(temp), "ShutdownChunkUnloadTest"], check=True, env=env)
    print(f"Shutdown native scheduling: {validate_native_scheduling()} bytecode assertions passed")


if __name__ == "__main__":
    main()

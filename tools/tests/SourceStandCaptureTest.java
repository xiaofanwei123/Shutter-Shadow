import io.github.mortuusars.exposure.util.cycles.task.Task;
import io.github.mortuusars.exposure.util.cycles.task.EmptyTask;
import io.github.mortuusars.exposure.util.ExtraData;
import com.xfw.shuttershadow.network.CameraSessionCloseC2S;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 执行生产源截图的委托、镜头判定和取消代码，仅替换世界与计时器环境。 */
public final class SourceStandCaptureTest {
    private static final String SOURCE = "com/xfw/shuttershadow/client/SourceStandCapture";
    private static final String TEST = "SourceStandCaptureTest";
    public static final Object ERROR = new Object();
    private static final Environment ENVIRONMENT = new Environment();
    private static final List<CameraSessionCloseC2S> PACKETS = new ArrayList<>();
    private static final List<Long> REJECTED = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        nativeScreenshot();
        ClassNode source = read(Path.of(args[0]), SOURCE);
        var definitions = new HashMap<String, byte[]>();
        ClassNode action = read(Path.of(args[0]), SOURCE + "$ScopeAction");
        clean(action, "SourceActionFixture", "java/lang/Object");
        action.interfaces.clear();
        for (FieldNode field : action.fields) {
            field.desc = "L" + TEST + "$Stand;";
            field.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        }
        for (MethodNode method : action.methods) {
            if (method.name.equals("<init>")) {
                method.access = Opcodes.ACC_PUBLIC;
                method.desc = "(L" + TEST + "$Stand;)V";
            }
            environment(method);
        }
        definitions.put(action.name, bytes(action));
        clean(source, "SourceScopeFixture", "java/lang/Object");
        source.fields.removeIf(field -> !field.name.equals("renderingScope"));
        source.fields.getFirst().desc = "LSourceActionFixture;";
        source.fields.getFirst().access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        source.methods.removeIf(method -> !method.name.equals("isRenderingSourceScene"));
        environment(source.methods.getFirst());
        definitions.put(source.name, bytes(source));

        ClassNode cancel = read(Path.of(args[0]), SOURCE);
        clean(cancel, "SourceCancelFixture", TEST + "$Lifecycle");
        cancel.fields.removeIf(field -> !field.name.equals("screenshot"));
        cancel.fields.getFirst().desc = "L" + TEST + "$Screenshot;";
        cancel.fields.getFirst().access = Opcodes.ACC_PUBLIC;
        cancel.methods.removeIf(method -> !method.name.equals("cancel") || !method.desc.equals("()V"));
        cancel.methods.getFirst().access = Opcodes.ACC_PUBLIC;
        environment(cancel.methods.getFirst());
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cancel.superName, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        cancel.methods.add(constructor);
        definitions.put(cancel.name, bytes(cancel));
        ClassNode early = read(Path.of(args[0]), SOURCE);
        clean(early, "SourceEarlyFixture", "java/lang/Object");
        early.fields.clear();
        early.methods.removeIf(method -> !method.name.equals("failBeforeCapture")
                && !method.name.startsWith("lambda$failBeforeCapture$"));
        check(early.methods.size() == 2, "early failure fixture must execute helper and its original connection callback");
        for (MethodNode method : early.methods) {
            method.desc = descriptor(method.desc);
            environment(method);
        }
        definitions.put(early.name, bytes(early));
        ClassNode rejection = read(Path.of(args[0]), "com/xfw/shuttershadow/mixin/exposure/CameraCaptureTemplateRemoteMixin");
        clean(rejection, "SourceRejectionFixture", "java/lang/Object");
        rejection.access &= ~Opcodes.ACC_ABSTRACT;
        rejection.fields.clear();
        rejection.methods.removeIf(method -> !method.name.equals("<init>")
                && !method.name.equals("shuttershadow$rejectEmptySourceCapture"));
        for (MethodNode method : rejection.methods) {
            method.desc = descriptor(method.desc);
            method.visibleAnnotations = method.invisibleAnnotations = null;
            environment(method);
        }
        definitions.put(rejection.name, bytes(rejection));
        var loader = new ClassLoader(SourceStandCaptureTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] definition = definitions.get(name);
                if (definition == null) throw new ClassNotFoundException(name);
                return defineClass(name, definition, 0, definition.length);
            }
        };
        scope(loader);
        cancellation(loader);
        earlyFailure(loader);
        emptyTaskRejection(loader);
        System.out.println("Source stand capture lifecycle checks passed: " + checks);
    }

    private static void nativeScreenshot() throws Exception {
        Class<?> wrapper = Class.forName(SOURCE.replace('/', '.') + "$NativeScreenshot");
        var constructor = wrapper.getDeclaredConstructor(Task.class);
        constructor.setAccessible(true);
        var delegate = new Delegate();
        Task<?> screenshot = (Task<?>) constructor.newInstance(delegate);
        check(screenshot.execute() == delegate.future, "wrapper must return the original native future");
        var field = wrapper.getDeclaredField("future");
        field.setAccessible(true);
        check(field.get(screenshot) == delegate.future, "cancellation must retain original native future identity");
        screenshot.tick();
        screenshot.tick();
        check(delegate.ticks == 2, "native screenshot ticks must be forwarded unchanged");
        delegate.future.complete("native image result");
        check(screenshot.execute().join().equals("native image result"), "native result must pass through unchanged");
    }

    private static void scope(ClassLoader loader) throws Exception {
        Class<?> type = loader.loadClass("SourceScopeFixture");
        Class<?> action = loader.loadClass("SourceActionFixture");
        var field = type.getField("renderingScope");
        var predicate = type.getMethod("isRenderingSourceScene");
        var before = action.getMethod("beforeCapture");
        var after = action.getMethod("afterCapture");
        Object level = new Object();
        var stand = new Stand(level);
        Object first = action.getConstructor(Stand.class).newInstance(stand);
        Object second = action.getConstructor(Stand.class).newInstance(stand);
        ENVIRONMENT.level = level;
        ENVIRONMENT.camera = stand;
        check(!(boolean) predicate.invoke(null), "normal rendering has no source capture scope");
        before.invoke(first);
        check((boolean) predicate.invoke(null), "source stand camera enters scope");
        ENVIRONMENT.camera = new Object();
        check(!(boolean) predicate.invoke(null), "ordinary player camera must stay outside source scope");
        ENVIRONMENT.camera = stand;
        ENVIRONMENT.level = new Object();
        check(!(boolean) predicate.invoke(null), "manual target dimension must stay outside source scope");
        ENVIRONMENT.level = level;
        before.invoke(second);
        after.invoke(first);
        check(field.get(null) == second && (boolean) predicate.invoke(null), "stale completion must not clear a newer scope");
        after.invoke(second);
        check(field.get(null) == null && !(boolean) predicate.invoke(null), "source completion restores ordinary rendering");
    }

    private static void cancellation(ClassLoader loader) throws Exception {
        Class<?> type = loader.loadClass("SourceCancelFixture");
        var cancel = type.getMethod("cancel");
        var screenshotField = type.getField("screenshot");
        Lifecycle early = (Lifecycle) type.getConstructor().newInstance();
        screenshotField.set(early, new Screenshot());
        cancel.invoke(early);
        check(early.timer.paused && early.done && early.completableFuture.join() == ERROR,
                "pre-exposure cancel must stop timer and finish capture with error");
        cancel.invoke(early);
        check(early.completableFuture.join() == ERROR, "completed capture cancellation is harmless");
        Lifecycle started = (Lifecycle) type.getConstructor().newInstance();
        var screenshot = new Screenshot();
        screenshot.future = new CompletableFuture<>();
        boolean[] nativeCleanup = {false};
        screenshot.future.thenAccept(result -> {
            nativeCleanup[0] = true;
            started.done = true;
            started.completableFuture.complete(result);
        });
        screenshotField.set(started, screenshot);
        cancel.invoke(started);
        check(started.timer.paused && screenshot.future.join() == ERROR,
                "started cancellation must complete the original native future");
        check(nativeCleanup[0] && started.completableFuture.join() == ERROR,
                "original completion chain must run restoration and outer completion");
        Lifecycle completed = (Lifecycle) type.getConstructor().newInstance();
        completed.done = true;
        screenshotField.set(completed, new Screenshot());
        cancel.invoke(completed);
        check(!completed.timer.paused && !completed.completableFuture.isDone(), "already finished task has no cancellation side effects");
    }

    private static void clean(ClassNode node, String name, String parent) {
        node.name = name;
        node.access = (node.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        node.superName = parent;
        node.signature = null;
        node.visibleAnnotations = node.invisibleAnnotations = null;
        node.nestHostClass = null;
        node.nestMembers = null;
        node.innerClasses.clear();
        for (FieldNode field : node.fields) { field.signature = null; field.visibleAnnotations = field.invisibleAnnotations = null; }
        for (MethodNode method : node.methods) { method.signature = null; method.localVariables = null; }
    }

    private static void environment(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode) method.instructions.remove(instruction);
            else if (instruction instanceof FieldInsnNode field) {
                if (field.name.equals("renderingScope") && field.owner.equals(SOURCE)) {
                    field.owner = "SourceScopeFixture"; field.desc = "LSourceActionFixture;";
                } else if (field.owner.equals(SOURCE + "$ScopeAction")) {
                    field.owner = "SourceActionFixture"; field.desc = "L" + TEST + "$Stand;";
                } else if (field.owner.equals("net/minecraft/client/Minecraft") && field.name.equals("level")) {
                    field.owner = TEST + "$Environment"; field.desc = "Ljava/lang/Object;";
                } else if (field.name.equals("screenshot")) {
                    field.owner = "SourceCancelFixture"; field.desc = "L" + TEST + "$Screenshot;";
                } else if (field.name.equals("future") && field.owner.equals(SOURCE + "$NativeScreenshot")) {
                    field.owner = TEST + "$Screenshot";
                } else if (field.name.equals("timer")) {
                    field.owner = TEST + "$Lifecycle"; field.desc = "L" + TEST + "$Timer;";
                } else if (field.name.equals("completableFuture")) {
                    field.owner = TEST + "$Lifecycle";
                } else if (field.name.equals("ERROR_FAILED_GENERIC")) {
                    field.owner = TEST; field.name = "ERROR"; field.desc = "Ljava/lang/Object;";
                }
            } else if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getInstance")) {
                    call.owner = TEST; call.name = "environment"; call.desc = "()L" + TEST + "$Environment;";
                } else if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getCameraEntity")) {
                    call.owner = TEST + "$Environment"; call.desc = "()Ljava/lang/Object;";
                } else if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getConnection")) {
                    call.owner = TEST + "$Environment"; call.desc = "()Ljava/lang/Object;";
                } else if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("execute")) {
                    call.owner = TEST + "$Environment";
                } else if (call.owner.equals("net/neoforged/neoforge/network/PacketDistributor")) {
                    call.owner = TEST;
                } else if (call.owner.equals("io/github/mortuusars/exposure/world/camera/capture/CaptureParameters")) {
                    call.owner = TEST + "$Parameters";
                } else if (call.owner.equals("io/github/mortuusars/exposure/world/entity/CameraStandEntity") && call.name.equals("level")) {
                    call.owner = TEST + "$Stand"; call.desc = "()Ljava/lang/Object;";
                } else if (call.owner.equals(SOURCE + "$ScopeAction")) {
                    call.owner = "SourceActionFixture";
                } else if (call.name.equals("isDone") || call.name.equals("setDone")) {
                    call.owner = TEST + "$Lifecycle";
                } else if (call.owner.equals("io/github/mortuusars/exposure/client/capture/CaptureTimer")) {
                    call.owner = TEST + "$Timer";
                } else if (call.owner.equals("io/github/mortuusars/exposure/util/cycles/task/Result") && call.name.equals("error")) {
                    call.owner = TEST; call.desc = "(Ljava/lang/Object;)Ljava/lang/Object;";
                }
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                dynamic.desc = descriptor(dynamic.desc);
                for (int i = 0; i < dynamic.bsmArgs.length; i++) {
                    if (dynamic.bsmArgs[i] instanceof Handle handle && handle.getOwner().equals(SOURCE)) {
                        dynamic.bsmArgs[i] = new Handle(handle.getTag(),
                                handle.getName().equals("failBeforeCapture") ? TEST : "SourceEarlyFixture",
                                handle.getName().equals("failBeforeCapture") ? "rejected" : handle.getName(),
                                descriptor(handle.getDesc()), handle.isInterface());
                    } else if (dynamic.bsmArgs[i] instanceof Type type) {
                        dynamic.bsmArgs[i] = Type.getType(descriptor(type.getDescriptor()));
                    }
                }
            }
        }
    }

    private static String descriptor(String original) {
        return original.replace("Lnet/minecraft/client/Minecraft;", "L" + TEST + "$Environment;")
                .replace("Lnet/minecraft/client/multiplayer/ClientPacketListener;", "Ljava/lang/Object;")
                .replace("Lio/github/mortuusars/exposure/world/camera/capture/CaptureParameters;", "L" + TEST + "$Parameters;");
    }

    private static void earlyFailure(ClassLoader loader) throws Exception {
        var fail = loader.loadClass("SourceEarlyFixture").getMethod("failBeforeCapture", long.class);
        ENVIRONMENT.connection = null;
        ENVIRONMENT.queued = null;
        fail.invoke(null, -17);
        check(ENVIRONMENT.queued == null && PACKETS.isEmpty(), "disconnected early abort must not enqueue a reply");
        ENVIRONMENT.connection = new Object();
        fail.invoke(null, -18);
        check(ENVIRONMENT.queued != null && PACKETS.isEmpty(), "early abort must marshal its reply onto the client executor");
        ENVIRONMENT.queued.run();
        check(PACKETS.equals(List.of(new CameraSessionCloseC2S(-18, false))), "valid connection receives the source failure transaction");
        PACKETS.clear();
        fail.invoke(null, -19);
        ENVIRONMENT.connection = new Object();
        ENVIRONMENT.queued.run();
        check(PACKETS.isEmpty(), "a replacement connection must not receive an old failure reply");
    }

    private static void emptyTaskRejection(ClassLoader loader) throws Exception {
        var type = loader.loadClass("SourceRejectionFixture");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        var instance = constructor.newInstance();
        var reject = type.getDeclaredMethod("shuttershadow$rejectEmptySourceCapture", Parameters.class, CallbackInfoReturnable.class);
        reject.setAccessible(true);
        var ordinary = new Parameters(new ExtraData());
        var source = new Parameters(new ExtraData());
        source.extraData().put(CameraSessionCloseC2S.SOURCE_CAPTURE_SEQUENCE, -20L);
        reject.invoke(instance, ordinary, new CallbackInfoReturnable<>("createTask", false, new EmptyTask<>()));
        check(REJECTED.isEmpty(), "ordinary and manual empty tasks must not reject a redstone transaction");
        reject.invoke(instance, source, new CallbackInfoReturnable<>("createTask", false, new Delegate()));
        check(REJECTED.isEmpty(), "valid native source screenshot must not be rejected");
        reject.invoke(instance, source, new CallbackInfoReturnable<>("createTask", false, new EmptyTask<>()));
        check(REJECTED.equals(List.of(-20L)), "empty source task must send its exact failed transaction");
    }

    private static ClassNode read(Path compiled, String name) throws Exception {
        var node = new ClassNode();
        new ClassReader(Files.readAllBytes(compiled.resolve(name + ".class"))).accept(node, 0);
        return node;
    }
    private static byte[] bytes(ClassNode node) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
    public static Environment environment() { return ENVIRONMENT; }
    public static Object error(Object ignored) { return ERROR; }
    public static void sendToServer(CustomPacketPayload first, CustomPacketPayload... rest) {
        PACKETS.add((CameraSessionCloseC2S) first);
        check(rest.length == 0, "source completion must send exactly one packet");
    }
    public static void rejected(long sequence) { REJECTED.add(sequence); }
    public record Parameters(ExtraData extraData) {}
    public static final class Environment {
        public Object camera; public Object level; public Object connection; public Runnable queued;
        public Object getCameraEntity() { return camera; }
        public Object getConnection() { return connection; }
        public void execute(Runnable work) { queued = work; }
    }
    public static final class Stand { private final Object level; public Stand(Object level) { this.level = level; } public Object level() { return level; } }
    public static final class Screenshot { public CompletableFuture<Object> future; }
    public static final class Timer { public boolean paused; public void pause() { paused = true; } }
    public static class Lifecycle {
        public boolean done;
        public final Timer timer = new Timer();
        public final CompletableFuture<Object> completableFuture = new CompletableFuture<>();
        public Lifecycle() {}
        public boolean isDone() { return done; }
        public void setDone() { done = true; }
    }
    private static final class Delegate extends Task<String> {
        final CompletableFuture<String> future = new CompletableFuture<>();
        int ticks;
        @Override public CompletableFuture<String> execute() { return future; }
        @Override public void tick() { ticks++; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

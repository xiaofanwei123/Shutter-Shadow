import com.xfw.shuttershadow.compat.IrisInterface;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** 执行编译后的生产注入器，仅替换环境入口；不复制判定逻辑、不创建 GL 上下文。 */
public final class IrisCameraDepthTargetTest {
    private static int checks;
    private static boolean camera;
    private static boolean background;
    private static Object target;
    private static int targetReads;

    public static void main(String[] args) throws Exception {
        boolean irisAbsent = false;
        try { Class.forName("net.irisshaders.iris.Iris", false, IrisCameraDepthTargetTest.class.getClassLoader()); }
        catch (ClassNotFoundException expected) { irisAbsent = true; }
        check(irisAbsent, "state test must run without Iris on its classpath");
        check(!new IrisInterface.Invoker().isRenderingShadowMap(), "optional Iris invoker loads without Iris");
        Class<?> type = fixture(Path.of(args[0]));
        Object first = type.getConstructor().newInstance();
        Object second = type.getConstructor().newInstance();
        Object mainTarget = new Object();
        Object cameraTarget = new Object();
        commit(first, 5, 100);
        scope(false, false, mainTarget);
        head(first, 5, 200);
        check(version(first) == 5 && !pending(first) && identity(first) == null,
                "ordinary rendering must not change version or camera state");
        check(targetReads == 0, "ordinary rendering must not even query Minecraft's target");
        scope(true, false, cameraTarget);
        head(first, 5, 200);
        check(!pending(first) && version(first) == 5, "camera flag alone must not enter screenshot scope");
        scope(false, true, cameraTarget);
        head(first, 5, 200);
        check(!pending(first) && version(first) == 5, "unrelated Exposure capture must not enter camera scope");
        check(targetReads == 0, "both capture flags are required before target lookup");

        scope(true, true, cameraTarget);
        head(first, 5, 200);
        check(pending(first) && identity(first) == cameraTarget, "camera capture retains restoration state and target identity");
        check(version(first) == (5 ^ 1), "same version and changed depth must invalidate native version cache");
        check(depth(first) == 100, "injector must leave native depth texture replacement to Iris");
        commit(first, 5, 200); // 模拟原生 Iris 已完成更新，不模拟其附件/GL 实现。
        finish(first, 5, 200);
        check(pending(first) && identity(first) == cameraTarget, "camera RETURN must retain future restoration requirement");
        head(first, 5, 200);
        check(version(first) == 5, "same target object and depth ID must not repeat invalidation");

        Object recycledTarget = new Object();
        scope(true, true, recycledTarget);
        head(first, 5, 200);
        check(version(first) == (5 ^ 1), "new target with recycled same texture ID must rebind");
        check(identity(first) == recycledTarget, "recycled-ID target identity must be retained");
        commit(first, 5, 200);
        head(first, 6, 300);
        check(version(first) == 5, "already changed native version must be handled by Iris");
        check(pending(first), "native version change must not discard restoration state");
        commit(first, 6, 300);

        scope(false, true, new Object());
        head(first, 6, 400);
        check(version(first) == (6 ^ 1), "pending camera cleanup must also handle a following Exposure target");
        commit(first, 6, 400);
        finish(first, 6, 400);
        check(pending(first) && identity(first) == target, "unrelated background RETURN must defer stable-target cleanup");

        scope(false, false, mainTarget);
        head(first, 6, 100);
        check(version(first) == (6 ^ 1), "normal target restore must force native reattachment");
        check(pending(first), "HEAD must retain state if native resize throws before RETURN");
        commit(first, 6, 100);
        finish(first, 6, 100);
        check(!pending(first) && identity(first) == null, "normal RETURN must clear pending state and target reference");
        int lookupsAfterCleanup = targetReads;
        head(first, 6, 999);
        check(version(first) == 6 && targetReads == lookupsAfterCleanup, "later player frames must not enter camera cleanup");

        commit(second, 11, 700);
        scope(true, true, cameraTarget);
        head(second, 11, 700);
        check(version(second) == (11 ^ 1) && pending(second), "initial identical depth ID still requires camera lifetime tracking");
        check(!pending(first) && version(first) == 6 && identity(first) == null, "pipeline instances must keep independent state");
        commit(second, 11, 700);
        scope(false, false, new Object());
        head(second, 11, 700);
        check(version(second) == (11 ^ 1), "same-ID restored target must reattach after initial same-ID camera entry");
        finish(second, 11, 700);
        check(!pending(second) && identity(second) == null, "stable RETURN clears identity even after deferred restoration");

        commit(second, Integer.MAX_VALUE, 700);
        scope(true, true, cameraTarget);
        head(second, Integer.MAX_VALUE, 800);
        check(version(second) == (Integer.MAX_VALUE ^ 1), "maximum version must invalidate without overflow assumptions");
        commit(second, Integer.MIN_VALUE, 800);
        scope(true, true, new Object());
        head(second, Integer.MIN_VALUE, 800);
        check(version(second) == (Integer.MIN_VALUE ^ 1), "minimum version must also differ using XOR");
        commit(second, 23, 900);
        scope(true, true, cameraTarget);
        try {
            head(second, 23, 901);
            commit(second, 23, 901);
            throw new IllegalStateException("native resize aborted before RETURN");
        } catch (IllegalStateException expected) {
            check(pending(second) && identity(second) == cameraTarget,
                    "missing RETURN after native failure must retain cleanup state");
        }
        scope(false, false, mainTarget);
        head(second, 23, 900);
        check(version(second) == (23 ^ 1), "next stable target must recover after failed native resize");
        System.out.println("Iris camera depth target lifecycle checks passed: " + checks);
    }

    private static Class<?> fixture(Path compiled) throws Exception {
        var node = new ClassNode();
        new ClassReader(Files.readAllBytes(compiled.resolve(
                "com/xfw/shuttershadow/mixin/compat/iris/IrisCameraDepthTargetMixin.class"))).accept(node, 0);
        String originalName = node.name;
        node.name = "IrisCameraDepthFixture";
        node.access = (node.access & ~Opcodes.ACC_ABSTRACT) | Opcodes.ACC_PUBLIC;
        node.visibleAnnotations = node.invisibleAnnotations = null;
        for (FieldNode field : node.fields) {
            field.visibleAnnotations = field.invisibleAnnotations = null;
            field.signature = null;
            if (field.desc.startsWith("L")) field.desc = "Ljava/lang/Object;";
        }
        int targetFactory = 0, targetLookup = 0;
        for (MethodNode method : node.methods) {
            method.visibleAnnotations = method.invisibleAnnotations = null;
            method.visibleParameterAnnotations = method.invisibleParameterAnnotations = null;
            method.visibleTypeAnnotations = method.invisibleTypeAnnotations = null;
            method.visibleLocalVariableAnnotations = method.invisibleLocalVariableAnnotations = null;
            method.localVariables = null;
            method.signature = null;
            Type[] arguments = Type.getArgumentTypes(method.desc);
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i].getSort() == Type.OBJECT) arguments[i] = Type.getType(Object.class);
            }
            method.desc = Type.getMethodDescriptor(Type.getReturnType(method.desc), arguments);
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FrameNode) method.instructions.remove(instruction);
                if (instruction instanceof FieldInsnNode field && field.owner.equals(originalName)) {
                    field.owner = node.name;
                    if (field.desc.startsWith("L")) field.desc = "Ljava/lang/Object;";
                }
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getInstance")) {
                    method.instructions.remove(call);
                    targetFactory++;
                } else if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getMainRenderTarget")) {
                    replace(call, "currentTarget", "()Ljava/lang/Object;");
                    targetLookup++;
                } else if (call.owner.equals("com/xfw/shuttershadow/client/RemoteStandCapture")) {
                    if (call.name.equals("isRenderingScreenshot")) replace(call, "cameraScope", "()Z");
                    else throw new AssertionError("Unexpected capture dependency: " + call.name);
                } else if (call.owner.equals("io/github/mortuusars/exposure/client/capture/task/BackgroundScreenshotCaptureTask")
                        && call.name.equals("isCapturing")) replace(call, "backgroundScope", "()Z");
            }
        }
        check(targetFactory == 1 && targetLookup == 1,
                "fixture must replace exactly the external target environment calls");
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(IrisCameraDepthTargetTest.class.getClassLoader()) {
            Class<?> loadFixture() { return defineClass("IrisCameraDepthFixture", bytes, 0, bytes.length); }
        }.loadFixture();
    }

    private static void replace(MethodInsnNode call, String name, String descriptor) {
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.owner = "IrisCameraDepthTargetTest";
        call.name = name;
        call.desc = descriptor;
        call.itf = false;
    }

    public static boolean cameraScope() { return camera; }
    public static boolean backgroundScope() { return background; }
    public static Object currentTarget() { targetReads++; return target; }

    private static void scope(boolean cameraActive, boolean backgroundActive, Object renderTarget) {
        camera = cameraActive;
        background = backgroundActive;
        target = renderTarget;
    }

    private static void head(Object fixture, int version, int depth) throws Exception {
        invoke(fixture, "shuttershadow$checkCameraDepth", version, depth);
    }

    private static void finish(Object fixture, int version, int depth) throws Exception {
        invoke(fixture, "shuttershadow$finishCameraDepth", version, depth);
    }

    private static void invoke(Object fixture, String methodName, int version, int depth) throws Exception {
        Method method = fixture.getClass().getDeclaredMethod(methodName,
                int.class, int.class, int.class, int.class, Object.class, Object.class, Object.class);
        method.setAccessible(true);
        method.invoke(fixture, version, depth, 64, 64, null, null, null);
    }

    private static Field field(Object fixture, String name) throws Exception {
        var field = fixture.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void commit(Object fixture, int version, int depth) throws Exception {
        field(fixture, "cachedDepthBufferVersion").setInt(fixture, version);
        field(fixture, "currentDepthTexture").setInt(fixture, depth);
    }

    private static int version(Object fixture) throws Exception { return field(fixture, "cachedDepthBufferVersion").getInt(fixture); }
    private static int depth(Object fixture) throws Exception { return field(fixture, "currentDepthTexture").getInt(fixture); }
    private static boolean pending(Object fixture) throws Exception { return field(fixture, "shuttershadow$cameraDepthPending").getBoolean(fixture); }
    private static Object identity(Object fixture) throws Exception { return field(fixture, "shuttershadow$cameraTarget").get(fixture); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

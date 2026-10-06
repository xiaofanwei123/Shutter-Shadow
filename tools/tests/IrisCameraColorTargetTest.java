import com.xfw.shuttershadow.compat.IrisInterface;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/** 执行编译后的最终颜色注入器，仅替换外部状态入口，不复制判定或运行 GL。 */
public final class IrisCameraColorTargetTest {
    private static int checks;
    private static boolean camera;
    private static boolean background;
    private static Target target;
    private static int targetReads;
    private static int textureReads;
    private static int versionReads;

    public static void main(String[] args) throws Exception {
        boolean irisAbsent = false;
        try { Class.forName("net.irisshaders.iris.Iris", false, IrisCameraColorTargetTest.class.getClassLoader()); }
        catch (ClassNotFoundException expected) { irisAbsent = true; }
        check(irisAbsent, "state test must run without Iris on its classpath");
        check(!new IrisInterface.Invoker().isRenderingShadowMap(), "default Iris invoker loads without optional Iris");
        Class<?> type = fixture(Path.of(args[0]));
        Object first = type.getConstructor().newInstance();
        Object second = type.getConstructor().newInstance();
        var main = new Target(100, 5);
        var screenshot = new Target(100, 5);
        commit(first, 5, 100);
        scope(false, false, main);
        head(first);
        check(version(first) == 5 && !pending(first) && identity(first) == null, "ordinary frames leave color state untouched");
        check(targetReads == 0 && versionReads == 0 && textureReads == 0,
                "ordinary frames must not query Minecraft or target textures");
        scope(true, false, screenshot);
        head(first);
        check(!pending(first) && version(first) == 5, "camera flag alone must not enter background scope");
        scope(false, true, screenshot);
        head(first);
        check(!pending(first) && version(first) == 5, "ordinary Exposure background must not enter camera scope");

        scope(true, true, screenshot);
        head(first);
        check(pending(first) && identity(first) == screenshot, "first camera entry tracks target identity even with identical color");
        check(version(first) == (5 ^ 1), "new target with same color ID and version invalidates native cache");
        check(texture(first) == 100, "injector leaves native attachment ID updates to Iris");
        commit(first, 5, 100);
        finish(first);
        check(pending(first) && identity(first) == screenshot, "camera RETURN retains stable-target restoration");
        int reads = versionReads;
        head(first);
        check(version(first) == 5, "same target object must not repeatedly invalidate native cache");
        check(versionReads == reads, "same object must skip color texture metadata reads");
        var recycled = new Target(100, 5);
        scope(true, true, recycled);
        head(first);
        check(version(first) == (5 ^ 1), "same-ID new object must trigger another attachment update");
        commit(first, 5, 100);

        scope(true, true, new Target(100, 6));
        head(first);
        check(version(first) == 5, "changed color version is already handled by native Iris");
        commit(first, 6, 100);
        scope(true, true, new Target(200, 6));
        head(first);
        check(version(first) == 6, "changed color ID is already handled by native Iris");
        commit(first, 6, 200);
        var followingExposure = new Target(200, 6);
        scope(false, true, followingExposure);
        head(first);
        check(version(first) == (6 ^ 1), "pending cleanup also detects recycled IDs in a following Exposure capture");
        commit(first, 6, 200);
        finish(first);
        check(pending(first) && identity(first) == followingExposure, "background RETURN cannot clear restoration state early");
        var restored = new Target(200, 6);
        scope(false, false, restored);
        head(first);
        check(version(first) == (6 ^ 1), "stable normal target with reused ID and version must reattach");
        commit(first, 6, 200);
        finish(first);
        check(!pending(first) && identity(first) == null, "normal RETURN clears pending flag and target reference");
        int afterCleanup = targetReads;
        scope(false, false, new Target(200, 6));
        head(first);
        check(version(first) == 6 && targetReads == afterCleanup, "later ordinary targets remain outside camera correction");

        commit(second, 11, 700);
        var secondCapture = new Target(700, 11);
        scope(true, true, secondCapture);
        head(second);
        check(version(second) == (11 ^ 1) && pending(second), "each pipeline can independently enter identical-color camera capture");
        check(!pending(first) && version(first) == 6 && identity(first) == null, "pipeline instance state must not leak");
        commit(second, 11, 700);
        try { throw new IllegalStateException("native final pass aborted before RETURN"); }
        catch (IllegalStateException expected) {
            check(pending(second) && identity(second) == secondCapture, "native failure without RETURN retains recovery state");
        }
        scope(false, false, new Target(700, 11));
        head(second);
        check(version(second) == (11 ^ 1), "stable target recovers after missing camera RETURN");
        finish(second);
        check(!pending(second) && identity(second) == null, "recovered normal RETURN releases retained target");
        for (int boundary : new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            commit(second, boundary, 700);
            scope(true, true, new Target(700, boundary));
            head(second);
            check(version(second) == (boundary ^ 1), "color version invalidation handles integer boundary " + boundary);
        }
        System.out.println("Iris camera color target lifecycle checks passed: " + checks);
    }

    private static Class<?> fixture(Path compiled) throws Exception {
        var node = new ClassNode();
        new ClassReader(Files.readAllBytes(compiled.resolve(
                "com/xfw/shuttershadow/mixin/compat/iris/IrisCameraColorTargetMixin.class"))).accept(node, 0);
        String originalName = node.name;
        node.name = "IrisCameraColorFixture";
        node.access = (node.access & ~Opcodes.ACC_ABSTRACT) | Opcodes.ACC_PUBLIC;
        node.visibleAnnotations = node.invisibleAnnotations = null;
        for (FieldNode field : node.fields) {
            field.visibleAnnotations = field.invisibleAnnotations = null;
            field.signature = null;
            if (field.desc.startsWith("L")) field.desc = "Ljava/lang/Object;";
        }
        int factories = 0, lookups = 0, colorIds = 0, versions = 0;
        for (MethodNode method : node.methods) {
            method.visibleAnnotations = method.invisibleAnnotations = null;
            method.visibleParameterAnnotations = method.invisibleParameterAnnotations = null;
            method.visibleTypeAnnotations = method.invisibleTypeAnnotations = null;
            method.visibleLocalVariableAnnotations = method.invisibleLocalVariableAnnotations = null;
            method.localVariables = null;
            method.signature = null;
            Type[] arguments = Type.getArgumentTypes(method.desc);
            for (int i = 0; i < arguments.length; i++) if (arguments[i].getSort() == Type.OBJECT) arguments[i] = Type.getType(Object.class);
            method.desc = Type.getMethodDescriptor(Type.getReturnType(method.desc), arguments);
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FrameNode || instruction instanceof TypeInsnNode cast
                        && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals("net/irisshaders/iris/targets/Blaze3dRenderTargetExt")) {
                    method.instructions.remove(instruction);
                }
                if (instruction instanceof FieldInsnNode field && field.owner.equals(originalName)) {
                    field.owner = node.name;
                    if (field.desc.startsWith("L")) field.desc = "Ljava/lang/Object;";
                }
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getInstance")) {
                    method.instructions.remove(call); factories++;
                } else if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getMainRenderTarget")) {
                    replace(call, "currentTarget", "()Ljava/lang/Object;"); lookups++;
                } else if (call.owner.equals("com/mojang/blaze3d/pipeline/RenderTarget") && call.name.equals("getColorTextureId")) {
                    replace(call, "colorId", "(Ljava/lang/Object;)I"); colorIds++;
                } else if (call.owner.equals("net/irisshaders/iris/targets/Blaze3dRenderTargetExt") && call.name.equals("iris$getColorBufferVersion")) {
                    replace(call, "colorVersion", "(Ljava/lang/Object;)I"); versions++;
                } else if (call.owner.equals("com/xfw/shuttershadow/client/RemoteStandCapture")) {
                    if (call.name.equals("isRenderingScreenshot")) replace(call, "cameraScope", "()Z");
                    else throw new AssertionError("Unexpected capture dependency: " + call.name);
                } else if (call.owner.equals("io/github/mortuusars/exposure/client/capture/task/BackgroundScreenshotCaptureTask")
                        && call.name.equals("isCapturing")) replace(call, "backgroundScope", "()Z");
            }
        }
        check(factories == 1 && lookups == 1 && colorIds == 1 && versions == 1,
                "fixture replaces only production environment calls, exactly once each");
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(IrisCameraColorTargetTest.class.getClassLoader()) {
            Class<?> loadFixture() { return defineClass("IrisCameraColorFixture", bytes, 0, bytes.length); }
        }.loadFixture();
    }

    private static void replace(MethodInsnNode call, String name, String descriptor) {
        call.setOpcode(Opcodes.INVOKESTATIC); call.owner = "IrisCameraColorTargetTest";
        call.name = name; call.desc = descriptor; call.itf = false;
    }

    public static boolean cameraScope() { return camera; }
    public static boolean backgroundScope() { return background; }
    public static Object currentTarget() { targetReads++; return target; }
    public static int colorId(Object current) { textureReads++; return ((Target) current).id(); }
    public static int colorVersion(Object current) { versionReads++; return ((Target) current).version(); }
    private static void scope(boolean cameraActive, boolean backgroundActive, Target renderTarget) { camera = cameraActive; background = backgroundActive; target = renderTarget; }
    private static void head(Object fixture) throws Exception { invoke(fixture, "shuttershadow$checkCameraColor"); }
    private static void finish(Object fixture) throws Exception { invoke(fixture, "shuttershadow$finishCameraColor"); }
    private static void invoke(Object fixture, String name) throws Exception {
        var method = fixture.getClass().getDeclaredMethod(name, Object.class);
        method.setAccessible(true); method.invoke(fixture, new Object[]{null});
    }
    private static Field field(Object fixture, String name) throws Exception {
        var field = fixture.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void commit(Object fixture, int version, int texture) throws Exception {
        field(fixture, "lastColorTextureVersion").setInt(fixture, version); field(fixture, "lastColorTextureId").setInt(fixture, texture);
    }
    private static int version(Object fixture) throws Exception { return field(fixture, "lastColorTextureVersion").getInt(fixture); }
    private static int texture(Object fixture) throws Exception { return field(fixture, "lastColorTextureId").getInt(fixture); }
    private static boolean pending(Object fixture) throws Exception { return field(fixture, "shuttershadow$cameraColorPending").getBoolean(fixture); }
    private static Object identity(Object fixture) throws Exception { return field(fixture, "shuttershadow$cameraTarget").get(fixture); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private record Target(int id, int version) {}
}

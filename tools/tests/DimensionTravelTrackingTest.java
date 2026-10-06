import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/** 执行生产换维事件包装器，验证取消请求不会清除仍有效的相机订阅。 */
public final class DimensionTravelTrackingTest {
    private static final String SOURCE = "com/xfw/shuttershadow/mixin/minecraft/server/MixinServerPlayer";
    private static final String FIXTURE = "DimensionTravelTrackingFixture";
    private static final String TEST = "DimensionTravelTrackingTest";
    private static final String HOOK = "shuttershadow$clearCameraTrackingBeforeVanillaTransfer";
    private static final String EVENT_OWNER = "net/neoforged/neoforge/common/CommonHooks";
    private static final String EVENT_DESC = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceKey;)Z";
    private static final List<String> CALLS = new ArrayList<>();
    private static Player cleanedPlayer;
    private static int checks;

    public static void main(String[] args) throws Exception {
        ClassNode production = read(Files.readAllBytes(Path.of(args[0], SOURCE + ".class")));
        MethodNode hook = production.methods.stream().filter(method -> method.name.equals(HOOK)).findFirst().orElseThrow();
        AnnotationNode wrapper = hook.visibleAnnotations.stream()
                .filter(annotation -> annotation.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
        check(value(wrapper, "method").equals(List.of("changeDimension")), "hook must wrap only the native dimension change");
        List<?> sites = (List<?>) value(wrapper, "at");
        check(sites.size() == 1, "hook must wrap one precise permission site");
        AnnotationNode at = (AnnotationNode) sites.getFirst();
        check(value(at, "target").equals("L" + EVENT_OWNER + ";onTravelToDimension" + EVENT_DESC),
                "hook must wrap the exact NeoForge permission event call");
        check(Boolean.FALSE.equals(value(at, "remap")), "NeoForge call must retain its actual method name");
        try (ZipFile jar = new ZipFile(args[1])) {
            ClassNode nativePlayer = read(jar.getInputStream(jar.getEntry("net/minecraft/server/level/ServerPlayer.class")).readAllBytes());
            int targets = 0;
            for (MethodNode method : nativePlayer.methods) {
                if (!method.name.equals("changeDimension")) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(EVENT_OWNER)
                            && call.name.equals("onTravelToDimension") && call.desc.equals(EVENT_DESC)) targets++;
                }
            }
            check(targets == 1, "actual NeoForge ServerPlayer must contain exactly one matching event call");
        }
        Class<?> fixture = fixture(hook);
        Object instance = fixture.getConstructor().newInstance();
        var attempt = fixture.getMethod(HOOK, Player.class, String.class, Operation.class);
        Player player = new Player();
        for (boolean allowed : new boolean[] {false, true, false}) {
            CALLS.clear();
            cleanedPlayer = null;
            player.subscribed = true;
            Operation<Boolean> event = eventArgs -> {
                CALLS.add("event");
                check(eventArgs.length == 2 && eventArgs[0] == player && eventArgs[1].equals("target"),
                        "permission event must receive the original player and dimension");
                check(player.subscribed, "camera subscription must still exist while permission is evaluated");
                return allowed;
            };
            boolean result = (boolean) attempt.invoke(instance, player, "target", event);
            check(result == allowed, "permission result must be returned unchanged");
            check(CALLS.equals(allowed ? List.of("event", "cleanup") : List.of("event")),
                    "cleanup must run exactly once after an allowed event and never after cancellation");
            check(player.subscribed != allowed, "only successful permission may clear the subscription");
            check(cleanedPlayer == (allowed ? player : null), "cleanup must affect exactly the original player");
        }
        CALLS.clear();
        player.subscribed = true;
        try {
            attempt.invoke(instance, player, "target", (Operation<Boolean>) eventArgs -> {
                CALLS.add("event");
                throw new IllegalStateException("event failure");
            });
            throw new AssertionError("event failure must propagate");
        } catch (java.lang.reflect.InvocationTargetException exception) {
            check(exception.getCause() instanceof IllegalStateException, "event failure must propagate unchanged");
            check(CALLS.equals(List.of("event")) && player.subscribed, "failed permission evaluation must preserve the subscription");
        }
        System.out.println("Dimension travel production hook passed " + checks + " checks.");
    }

    public static void removePlayerFromChunkTrackersAndEntityTrackers(Player player) {
        CALLS.add("cleanup");
        cleanedPlayer = player;
        player.subscribed = false;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        }
        throw new AssertionError("Missing annotation value: " + name);
    }

    private static Class<?> fixture(MethodNode hook) throws Exception {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = FIXTURE;
        node.superName = "java/lang/Object";
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
        hook.access = Opcodes.ACC_PUBLIC;
        hook.signature = null;
        hook.visibleAnnotations = hook.invisibleAnnotations = null;
        node.methods.add(hook);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(Map.of(
                SOURCE, FIXTURE,
                "net/minecraft/world/entity/Entity", TEST + "$Player",
                "net/minecraft/server/level/ServerPlayer", TEST + "$Player",
                "net/minecraft/resources/ResourceKey", "java/lang/String",
                "com/xfw/shuttershadow/core/chunk_loading/RemoteChunkTracking", TEST))));
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(DimensionTravelTrackingTest.class.getClassLoader()) {
            Class<?> defineFixture() { return defineClass(FIXTURE, bytes, 0, bytes.length); }
        }.defineFixture();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static class Player {
        boolean subscribed = true;
    }
}

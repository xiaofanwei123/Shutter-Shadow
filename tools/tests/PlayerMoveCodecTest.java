import com.xfw.shuttershadow.access.IEPlayerMoveC2SPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/** 将生产钩子插入真实四种包的编解码字节码，在实际网络缓冲区中校验线格式。 */
public final class PlayerMoveCodecTest {
    private static final String NATIVE = "net/minecraft/network/protocol/game/ServerboundMovePlayerPacket";
    private static final String BASE = "PlayerMoveCodecFixture";
    private static final String READ = "com/xfw/shuttershadow/mixin/minecraft/common/MixinServerboundMovePlayerPacketRead";
    private static final String WRITE = "com/xfw/shuttershadow/mixin/minecraft/client/MixinServerboundMovePlayerPacketWrite";
    private static final String DIMENSION = "com/xfw/shuttershadow/mixin/minecraft/common/MixinServerboundMovePlayerPacket_S";
    private static final String READ_HOOK = "shuttershadow$readDimension";
    private static final String WRITE_HOOK = "shuttershadow$writeDimension";
    private static final String BUFFER = "Lnet/minecraft/network/FriendlyByteBuf;";
    private static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static boolean supported;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path compiled = Path.of(args[0]);
        ClassNode readMixin = read(Files.readAllBytes(compiled.resolve(READ + ".class")));
        ClassNode writeMixin = read(Files.readAllBytes(compiled.resolve(WRITE + ".class")));
        List<String> variants = List.of("Pos", "PosRot", "Rot", "StatusOnly");
        Set<Type> targets = new HashSet<>();
        for (String variant : variants) targets.add(Type.getObjectType(NATIVE + "$" + variant));
        validateMixin(readMixin, targets, READ_HOOK, "read", true);
        validateMixin(writeMixin, targets, WRITE_HOOK, "write", false);
        try (ZipFile game = new ZipFile(args[1])) {
            FixtureLoader loader = new FixtureLoader();
            Map<String, String> mappings = new HashMap<>();
            mappings.put(NATIVE, BASE);
            for (String variant : variants) mappings.put(NATIVE + "$" + variant, BASE + "$" + variant);
            mappings.put("com/xfw/shuttershadow/network/CoreNetworkHandshake", "PlayerMoveCodecTest");
            ClassNode nativeBase = read(game.getInputStream(game.getEntry(NATIVE + ".class")).readAllBytes());
            ClassNode base = skeleton(NATIVE, "java/lang/Object");
            base.interfaces.add("com/xfw/shuttershadow/access/IEPlayerMoveC2SPacket");
            for (FieldNode field : nativeBase.fields) if ((field.access & Opcodes.ACC_STATIC) == 0) base.fields.add(field);
            base.methods.add(copy(named(nativeBase, "<init>")));
            ClassNode dimension = read(Files.readAllBytes(compiled.resolve(DIMENSION + ".class")));
            base.fields.add(dimension.fields.getFirst());
            for (String accessor : List.of("ip_getPlayerDimension", "ip_setPlayerDimension")) {
                base.methods.add(copy(named(dimension, accessor)));
            }
            mappings.put(DIMENSION, BASE);
            loader.define(BASE, remap(base, mappings));
            for (int i = 0; i < variants.size(); i++) {
                String variant = variants.get(i);
                String originalName = NATIVE + "$" + variant;
                String fixtureName = BASE + "$" + variant;
                ClassNode original = read(game.getInputStream(game.getEntry(originalName + ".class")).readAllBytes());
                check(original.methods.stream().filter(method -> method.name.equals("read")).count() == 1,
                        variant + " must have exactly one native read overload");
                check(original.methods.stream().filter(method -> method.name.equals("write")).count() == 1,
                        variant + " must have exactly one native write overload");
                MethodNode nativeRead = copy(named(original, "read"));
                MethodNode nativeWrite = copy(named(original, "write"));
                check(nativeRead.desc.equals("(" + BUFFER + ")L" + originalName + ";")
                                && (nativeRead.access & Opcodes.ACC_STATIC) != 0,
                        variant + " read signature and staticness must match the common handler");
                check(nativeWrite.desc.equals("(" + BUFFER + ")V") && (nativeWrite.access & Opcodes.ACC_STATIC) == 0,
                        variant + " write signature and staticness must match the client handler");
                MethodNode readHook = copy(named(readMixin, READ_HOOK));
                MethodNode writeHook = copy(named(writeMixin, WRITE_HOOK));
                ClassNode fixture = skeleton(originalName, NATIVE);
                fixture.methods.add(copy(named(original, "<init>")));
                fixture.methods.add(readHook);
                fixture.methods.add(writeHook);
                injectRead(nativeRead, originalName, readHook.desc);
                injectWrite(nativeWrite, originalName, writeHook.desc);
                nativeRead.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
                nativeWrite.access = Opcodes.ACC_PUBLIC;
                fixture.methods.add(nativeRead);
                fixture.methods.add(nativeWrite);
                Map<String, String> variantMappings = new HashMap<>(mappings);
                variantMappings.put(READ, fixtureName);
                variantMappings.put(WRITE, fixtureName);
                Class<?> packetClass = loader.define(fixtureName, remap(fixture, variantMappings));
                Object packet = switch (variant) {
                    case "Pos" -> packetClass.getConstructor(double.class, double.class, double.class, boolean.class)
                            .newInstance(13.5, -21.75, 47.125, true);
                    case "PosRot" -> packetClass.getConstructor(double.class, double.class, double.class, float.class, float.class, boolean.class)
                            .newInstance(13.5, -21.75, 47.125, 123.5f, -42.25f, false);
                    case "Rot" -> packetClass.getConstructor(float.class, float.class, boolean.class).newInstance(123.5f, -42.25f, true);
                    default -> packetClass.getConstructor(boolean.class).newInstance(false);
                };
                StreamCodec<FriendlyByteBuf, Object> codec = codec(packetClass);
                verifyCodec(packet, codec, new int[]{25, 33, 9, 1}[i], variant);
            }
        }
        System.out.println("Player movement production codecs passed " + checks + " checks.");
    }

    public static boolean doesServerHaveDimensionRuntime() { return supported; }

    private static void validateMixin(ClassNode mixin, Set<Type> targets, String hookName, String selector, boolean staticHook) {
        AnnotationNode annotation = mixin.invisibleAnnotations.stream().filter(value -> value.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
        check(new HashSet<>((List<?>) value(annotation, "value")).equals(targets), "merged hook must target exactly the four movement variants");
        check(mixin.fields.isEmpty(), "merged variant hook must not shadow or inject conflicting fields");
        MethodNode hook = named(mixin, hookName);
        AnnotationNode inject = hook.visibleAnnotations.stream().filter(value -> value.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        check(value(inject, "method").equals(List.of(selector)), "merged hook must select the shared method name");
        AnnotationNode at = (AnnotationNode) ((List<?>) value(inject, "at")).getFirst();
        check(value(at, "value").equals("RETURN"), "dimension suffix must follow all native packet fields");
        check(!Boolean.TRUE.equals(optionalValue(inject, "cancellable")), "suffix hooks must not cancel the native codec");
        check(((hook.access & Opcodes.ACC_STATIC) != 0) == staticHook, "shared callback staticness must match all targets");
        check(hook.desc.equals("(" + BUFFER + (staticHook ? "L" + CIR + ";" : "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;") + ")V"),
                "merged callback argument descriptor must match all variants");
    }

    private static void verifyCodec(Object packet, StreamCodec<FriendlyByteBuf, Object> codec, int nativeLength,
            String variant) throws Exception {
        IEPlayerMoveC2SPacket dimensional = (IEPlayerMoveC2SPacket) packet;
        for (ResourceKey<Level> dimension : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            dimensional.ip_setPlayerDimension(dimension);
            supported = true;
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            FriendlyByteBuf suffix = new FriendlyByteBuf(Unpooled.buffer());
            try {
                suffix.writeResourceKey(dimension);
                codec.encode(buffer, packet);
                check(buffer.writerIndex() == nativeLength + suffix.writerIndex(), variant + " dimension suffix length must remain unchanged");
                for (int j = 0; j < suffix.writerIndex(); j++) {
                    check(buffer.getByte(nativeLength + j) == suffix.getByte(j), variant + " must keep the original resource-key suffix bytes");
                }
                Object decoded = codec.decode(buffer);
                check(((IEPlayerMoveC2SPacket) decoded).ip_getPlayerDimension() == dimension, variant + " must retain the exact decoded dimension");
                check(buffer.readableBytes() == 0, variant + " must consume the entire native and dimension payload");
                for (java.lang.reflect.Field field : packet.getClass().getSuperclass().getDeclaredFields()) {
                    if (field.getName().equals("playerDimension")) continue;
                    field.setAccessible(true);
                    check(Objects.equals(field.get(packet), field.get(decoded)), variant + " must preserve native field " + field.getName());
                }
            } finally { buffer.release(); suffix.release(); }
        }
        for (ResourceKey<Level> dimension : Arrays.asList(Level.OVERWORLD, null)) {
            supported = false;
            dimensional.ip_setPlayerDimension(dimension);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                codec.encode(buffer, packet);
                check(buffer.writerIndex() == nativeLength, variant + " must keep vanilla payload when the server lacks runtime support");
            } finally { buffer.release(); }
        }
        supported = true;
        dimensional.ip_setPlayerDimension(null);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            try { codec.encode(buffer, packet); throw new AssertionError("null dimension must be rejected"); }
            catch (NullPointerException exception) {
                check(exception.getMessage().equals("player dimension is null"), variant + " must retain the production validation message");
                check(buffer.writerIndex() == nativeLength, variant + " rejected suffix must not append partial data");
            }
        } finally { buffer.release(); }
    }

    private static StreamCodec<FriendlyByteBuf, Object> codec(Class<?> packet) throws Exception {
        var write = packet.getMethod("write", FriendlyByteBuf.class);
        var read = packet.getMethod("read", FriendlyByteBuf.class);
        return StreamCodec.of((buffer, value) -> invoke(write, value, buffer), buffer -> invoke(read, null, buffer));
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object... args) {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            if (exception.getCause() instanceof Error error) throw error;
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static void injectRead(MethodNode method, String owner, String hookDescriptor) {
        int resultLocal = method.maxLocals++;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new TypeInsnNode(Opcodes.NEW, CIR));
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(new LdcInsnNode("read"));
            hook.add(new InsnNode(Opcodes.ICONST_0));
            hook.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
            hook.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CIR, "<init>", "(Ljava/lang/String;ZLjava/lang/Object;)V", false));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, READ_HOOK, hookDescriptor, false));
            hook.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
            method.instructions.insertBefore(instruction, hook);
        }
    }

    private static void injectWrite(MethodNode method, String owner, String hookDescriptor) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new InsnNode(Opcodes.ACONST_NULL));
            hook.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, WRITE_HOOK, hookDescriptor, false));
            method.instructions.insertBefore(instruction, hook);
        }
    }

    private static ClassNode skeleton(String name, String superclass) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = superclass;
        return node;
    }

    private static byte[] remap(ClassNode node, Map<String, String> mappings) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(mappings)));
        return writer.toByteArray();
    }

    private static MethodNode copy(MethodNode original) {
        MethodNode result = new MethodNode(original.access, original.name, original.desc, null, null);
        original.accept(result);
        result.signature = null;
        result.visibleAnnotations = result.invisibleAnnotations = null;
        return result;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static MethodNode named(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static Object optionalValue(AnnotationNode annotation, String name) {
        for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static Object value(AnnotationNode annotation, String name) {
        Object result = optionalValue(annotation, name);
        if (result == null) throw new AssertionError("Missing annotation value " + name);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader() { super(PlayerMoveCodecTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}

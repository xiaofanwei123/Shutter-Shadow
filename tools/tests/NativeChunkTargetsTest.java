import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

/** 核对编译后的同步 Mixin 与真实 NeoForge 游戏字节码，不加载游戏类。 */
public final class NativeChunkTargetsTest {
    private static int checks;
    private static final String PACKAGE = "com/xfw/shuttershadow/mixin/minecraft/server/";
    private static Path currentOverlay;

    public static void main(String[] args) throws Exception {
        currentOverlay = args.length > 2 ? Path.of(args[2]) : null;
        try (var game = new ZipFile(args[0])) {
            Path compiled = Path.of(args[1]);
            ClassNode sender = read(game, "net/minecraft/server/network/PlayerChunkSender");
            ClassNode senderMixin = read(compiled, PACKAGE + "MixinPlayerChunkSender");
            target(senderMixin, sender.name);
            var drop = named(senderMixin, "shuttershadow$keepCameraChunk");
            var redirect = annotation(drop, "/Redirect;");
            check(redirect != null, "native sender extension is a Redirect");
            MethodNode nativeDrop = selector(sender, selectors(redirect).getFirst());
            AnnotationNode callAt = at(redirect);
            check("INVOKE".equals(value(callAt, "value")), "drop callback redirects a native invocation");
            String invocation = (String) value(callAt, "target");
            check("Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V".equals(invocation),
                    "drop uses the expected game-listener send overload");
            var matchingCalls = calls(nativeDrop, invocation);
            check(matchingCalls.size() == 1, "drop contains exactly one matching unload send");
            MethodInsnNode nativeCall = matchingCalls.getFirst();
            var expectedArguments = new ArrayList<Type>();
            if (nativeCall.getOpcode() != Opcodes.INVOKESTATIC) expectedArguments.add(Type.getObjectType(nativeCall.owner));
            expectedArguments.addAll(Arrays.asList(Type.getArgumentTypes(nativeCall.desc)));
            expectedArguments.addAll(Arrays.asList(Type.getArgumentTypes(nativeDrop.desc)));
            check(Arrays.asList(Type.getArgumentTypes(drop.desc)).equals(expectedArguments),
                    "drop callback matches receiver, packet and both native method arguments");
            check(Type.getReturnType(drop.desc).equals(Type.getReturnType(nativeCall.desc)), "drop callback returns the exact invoked type");
            check((drop.access & Opcodes.ACC_STATIC) == (nativeDrop.access & Opcodes.ACC_STATIC), "drop callback staticness matches its host");
            check(senderMixin.methods.stream().noneMatch(method -> methodAnnotationSelectors(method).stream().anyMatch(
                            name -> name.contains("sendNextChunks") || name.contains("onChunkBatchReceivedByClient"))),
                    "native sender sending and acknowledgement remain untouched");

            ClassNode chunkMap = read(game, "net/minecraft/server/level/ChunkMap");
            ClassNode mapMixin = read(compiled, PACKAGE + "MixinChunkMap_C");
            target(mapMixin, chunkMap.name);
            shadows(mapMixin, chunkMap);
            inject(mapMixin, chunkMap, "shuttershadow$refreshExtraRange", "RETURN", false);
            inject(mapMixin, chunkMap, "shuttershadow$includeCameraWatchers", "RETURN", true);
            MethodNode biomeHook = named(mapMixin, "shuttershadow$routeBiomeUpdates");
            AnnotationNode biomeWrapper = annotation(biomeHook, "/WrapMethod;");
            check(biomeWrapper != null && selectors(biomeWrapper).equals(List.of("resendBiomesForChunks")),
                    "biome wrapper selects only the native resend operation");
            MethodNode biomeResend = selector(chunkMap, selectors(biomeWrapper).getFirst());
            check(biomeResend.desc.equals("(Ljava/util/List;)V") && biomeResend.signature.contains("List<Lnet/minecraft/world/level/chunk/ChunkAccess;>"),
                    "actual native biome method accepts List<ChunkAccess>");
            check(biomeHook.desc.equals("(Ljava/util/List;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V")
                            && (biomeHook.access & Opcodes.ACC_STATIC) == 0,
                    "biome wrapper matches the original list and void operation");
            check(instructionList(biomeResend).stream().anyMatch(instruction -> instruction instanceof MethodInsnNode call
                            && call.name.equals("getPlayers") && call.desc.equals("(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;")),
                    "native biome resend obtains the camera-extended chunk recipient list");
            check(chunkMap.methods.stream().filter(method -> method.name.startsWith("lambda$resendBiomesForChunks$")).anyMatch(method ->
                            instructionList(method).stream().anyMatch(instruction -> instruction instanceof MethodInsnNode call
                                    && call.owner.equals("net/minecraft/server/network/ServerGamePacketListenerImpl")
                                    && call.name.equals("send") && call.desc.equals("(Lnet/minecraft/network/protocol/Packet;)V"))),
                    "native biome resend sends through the listener covered by the routing context");

            ClassNode holder = read(game, "net/minecraft/server/level/ChunkHolder");
            ClassNode holderMixin = read(compiled, PACKAGE + "MixinChunkHolder");
            target(holderMixin, holder.name);
            shadows(holderMixin, holder);
            inject(holderMixin, holder, "shuttershadow$routeRemoteUpdates", "HEAD", true);

            ClassNode playerList = read(game, "net/minecraft/server/players/PlayerList");
            ClassNode listMixin = read(compiled, PACKAGE + "MixinPlayerList");
            target(listMixin, playerList.name);
            shadows(listMixin, playerList);
            inject(listMixin, playerList, "shuttershadow$broadcastRemoteDimension", "TAIL", false);
            inject(listMixin, playerList, "shuttershadow$broadcastRemotePosition", "TAIL", false);
            check(selectors(annotation(named(listMixin, "shuttershadow$broadcastRemoteDimension"), "/Inject;")).getFirst()
                            .equals("broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/resources/ResourceKey;)V"),
                    "dimension broadcast selects the full intended overload");
            check(selectors(annotation(named(listMixin, "shuttershadow$broadcastRemotePosition"), "/Inject;")).getFirst()
                            .equals("broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V"),
                    "position broadcast selects all four doubles in the full intended overload");

            ClassNode commonListener = read(game, "net/minecraft/server/network/ServerCommonPacketListenerImpl");
            ClassNode redirectMixin = read(compiled, PACKAGE + "MixinServerGamePacketListenerImpl_Redirect");
            target(redirectMixin, commonListener.name);
            shadows(redirectMixin, commonListener);
            var modify = named(redirectMixin, "modifyPacket");
            var modification = annotation(modify, "/ModifyVariable;");
            check(modification != null && Boolean.TRUE.equals(value(modification, "argsOnly")), "local raw wrapping modifies only an original method argument");
            MethodNode nativeSend = selector(commonListener, selectors(modification).getFirst());
            check("HEAD".equals(value(at(modification), "value")), "packet selection occurs at native send HEAD");
            Type packetType = Type.getObjectType("net/minecraft/network/protocol/Packet");
            check(Arrays.equals(Type.getArgumentTypes(modify.desc), new Type[]{packetType})
                            && Type.getReturnType(modify.desc).equals(packetType), "packet modifier signature matches raw native packet argument");
            check(Arrays.stream(Type.getArgumentTypes(nativeSend.desc)).filter(packetType::equals).count() == 1,
                    "selected native send has exactly one Packet argument");

            ClassNode loading = read(compiled, "com/xfw/shuttershadow/core/chunk_loading/PlayerChunkLoading");
            check(loading.methods.stream().flatMap(method -> instructionList(method).stream()).anyMatch(instruction ->
                            instruction instanceof MethodInsnNode call && call.owner.equals(holder.name) && call.name.equals("getChunkToSend")
                                    && call.desc.equals("()Lnet/minecraft/world/level/chunk/LevelChunk;")),
                    "production sender asks the actual fully ready-to-send chunk method");
            check(loading.methods.stream().flatMap(method -> instructionList(method).stream()).noneMatch(instruction ->
                            instruction instanceof MethodInsnNode call && call.owner.equals(holder.name) && call.name.equals("getTickingChunk")),
                    "production sender does not substitute merely ticking readiness");

            ClassNode clientMixin = read(compiled, "com/xfw/shuttershadow/mixin/minecraft/client/MixinClientPacketListener");
            check(clientMixin.methods.stream().noneMatch(method -> methodAnnotationSelectors(method).stream().anyMatch(name -> name.contains("ChunkBatch"))),
                    "native client batch listener is not intercepted by camera synchronization");
            ClassNode serverMixin = read(compiled, PACKAGE + "MixinServerGamePacketListenerImpl");
            check(serverMixin.methods.stream().noneMatch(method -> methodAnnotationSelectors(method).stream().anyMatch(name -> name.contains("ChunkBatch"))),
                    "native server batch listener is not intercepted by camera synchronization");
        }
        System.out.println("Native chunk Mixin bytecode checks passed: " + checks);
    }

    private static void inject(ClassNode mixin, ClassNode owner, String handlerName, String position, boolean cancellable) {
        var handler = named(mixin, handlerName);
        var annotation = annotation(handler, "/Inject;");
        check(annotation != null, handlerName + " has Inject annotation");
        check(position.equals(value(at(annotation), "value")), handlerName + " executes at the intended lifecycle position");
        check(Boolean.TRUE.equals(value(annotation, "cancellable")) == cancellable, handlerName + " cancellation matches its limited purpose");
        check(selectors(annotation).size() == 1, handlerName + " has exactly one method selector");
        MethodNode nativeMethod = selector(owner, selectors(annotation).getFirst());
        boolean returnsValue = !Type.getReturnType(nativeMethod.desc).equals(Type.VOID_TYPE);
        var expected = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(nativeMethod.desc)));
        expected.add(Type.getObjectType("org/spongepowered/asm/mixin/injection/callback/" + (returnsValue ? "CallbackInfoReturnable" : "CallbackInfo")));
        check(Arrays.asList(Type.getArgumentTypes(handler.desc)).equals(expected) && Type.getReturnType(handler.desc).equals(Type.VOID_TYPE),
                handlerName + " callback matches every native argument and return callback type");
        check((handler.access & Opcodes.ACC_STATIC) == (nativeMethod.access & Opcodes.ACC_STATIC), handlerName + " staticness matches native host");
        if (position.equals("TAIL") || position.equals("RETURN")) {
            check(instructionList(nativeMethod).stream().anyMatch(instruction -> instruction.getOpcode() >= Opcodes.IRETURN
                            && instruction.getOpcode() <= Opcodes.RETURN), handlerName + " has a native normal-return target");
        }
    }

    private static void shadows(ClassNode mixin, ClassNode owner) {
        for (FieldNode field : mixin.fields) {
            if (annotation(field.visibleAnnotations, "/Shadow;") == null && annotation(field.invisibleAnnotations, "/Shadow;") == null) continue;
            check(owner.fields.stream().anyMatch(nativeField -> nativeField.name.equals(field.name) && nativeField.desc.equals(field.desc)),
                    mixin.name + " exact shadow field " + field.name);
        }
        for (MethodNode method : mixin.methods) {
            if (annotation(method, "/Shadow;") == null) continue;
            check(owner.methods.stream().anyMatch(nativeMethod -> nativeMethod.name.equals(method.name) && nativeMethod.desc.equals(method.desc)),
                    mixin.name + " exact shadow method " + method.name);
        }
    }

    private static void target(ClassNode mixin, String owner) {
        AnnotationNode annotation = annotation(mixin.visibleAnnotations, "/Mixin;");
        if (annotation == null) annotation = annotation(mixin.invisibleAnnotations, "/Mixin;");
        check(annotation != null && List.of(Type.getObjectType(owner)).equals(value(annotation, "value")), mixin.name + " native Mixin target");
    }

    private static MethodNode selector(ClassNode owner, String selector) {
        String normalized = selector.startsWith("L") && selector.contains(";") ? selector.substring(selector.indexOf(';') + 1) : selector;
        int descriptorStart = normalized.indexOf('(');
        String name = descriptorStart < 0 ? normalized : normalized.substring(0, descriptorStart);
        String descriptor = descriptorStart < 0 ? null : normalized.substring(descriptorStart);
        var candidates = owner.methods.stream().filter(method -> method.name.equals(name) && (descriptor == null || method.desc.equals(descriptor))).toList();
        check(candidates.size() == 1, owner.name + " selector resolves exactly once: " + selector);
        return candidates.getFirst();
    }

    private static List<MethodInsnNode> calls(MethodNode method, String selector) {
        String owner = selector.substring(1, selector.indexOf(';'));
        String nameAndDescriptor = selector.substring(selector.indexOf(';') + 1);
        return instructionList(method).stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(owner) && (call.name + call.desc).equals(nameAndDescriptor)).toList();
    }

    private static List<AbstractInsnNode> instructionList(MethodNode method) {
        var result = new ArrayList<AbstractInsnNode>();
        method.instructions.forEach(result::add);
        return result;
    }

    private static List<String> methodAnnotationSelectors(MethodNode method) {
        var result = new ArrayList<String>();
        for (String suffix : List.of("/Inject;", "/Redirect;", "/ModifyVariable;", "/WrapOperation;")) {
            var annotation = annotation(method, suffix);
            if (annotation != null) result.addAll(selectors(annotation));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> selectors(AnnotationNode annotation) { return (List<String>) value(annotation, "method"); }
    private static AnnotationNode at(AnnotationNode annotation) { var value = value(annotation, "at"); return (AnnotationNode) (value instanceof List<?> list ? list.getFirst() : value); }
    private static MethodNode named(ClassNode owner, String name) { return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow(); }
    private static ClassNode read(ZipFile game, String name) throws Exception { var entry = game.getEntry(name + ".class"); check(entry != null, "native class exists: " + name); return read(game.getInputStream(entry).readAllBytes()); }
    private static ClassNode read(Path compiled, String name) throws Exception {
        Path overlay = currentOverlay == null ? null : currentOverlay.resolve(name + ".class");
        return read(Files.readAllBytes(overlay != null && Files.isRegularFile(overlay) ? overlay : compiled.resolve(name + ".class")));
    }
    private static ClassNode read(byte[] bytes) { var result = new ClassNode(); new ClassReader(bytes).accept(result, 0); return result; }
    private static AnnotationNode annotation(MethodNode method, String suffix) { var result = annotation(method.visibleAnnotations, suffix); return result == null ? annotation(method.invisibleAnnotations, suffix) : result; }
    private static AnnotationNode annotation(List<AnnotationNode> annotations, String suffix) { return annotations == null ? null : annotations.stream().filter(annotation -> annotation.desc.endsWith(suffix)).findFirst().orElse(null); }
    private static Object value(AnnotationNode annotation, String name) { if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1); return null; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/** 检查真实依赖字节码与编译后的兼容 Mixin，不启动 Minecraft。 */
public class RenderCompatTargetsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        try (var sodium = new ZipFile(args[0]); var iris = new ZipFile(args[1])) {
            var compiled = Path.of(args[2]);
            String mixinPackage = "com/xfw/shuttershadow/mixin/compat/sodium/";
            for (String name : List.of("IESodiumWorldRenderer", "MixinSodiumRenderSectionManager",
                    "MixinSodiumOcclusionCuller")) {
                ClassNode mixin = read(Files.readAllBytes(compiled.resolve(mixinPackage + name + ".class")));
                AnnotationNode annotation = annotation(mixin.invisibleAnnotations, "/Mixin;");
                check(annotation != null, name + " has Mixin annotation");
                Object value = value(annotation, "value");
                String target = value != null ? ((Type) ((List<?>) value).getFirst()).getInternalName()
                        : ((String) ((List<?>) value(annotation, "targets")).getFirst()).replace('.', '/');
                ClassNode owner = read(sodium.getInputStream(sodium.getEntry(target + ".class")).readAllBytes());
                for (FieldNode field : mixin.fields) {
                    if (annotation(field.visibleAnnotations, "/Shadow;") == null
                            && annotation(field.invisibleAnnotations, "/Shadow;") == null) continue;
                    check(owner.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)),
                            name + " shadow " + field.name);
                }
                for (MethodNode method : mixin.methods) {
                    if (annotation(method.visibleAnnotations, "/Shadow;") != null
                            || annotation(method.invisibleAnnotations, "/Shadow;") != null) {
                        check(owner.methods.stream().anyMatch(m -> m.name.equals(method.name) && m.desc.equals(method.desc)),
                                name + " exact shadow method " + method.name);
                    }
                    AnnotationNode invoker = annotation(method.visibleAnnotations, "/Invoker;");
                    if (invoker == null) invoker = annotation(method.invisibleAnnotations, "/Invoker;");
                    if (invoker != null) {
                        String methodName = (String) value(invoker, "value");
                        check(owner.methods.stream().anyMatch(m -> m.name.equals(methodName)
                                        && m.desc.equals(method.desc)
                                        && (m.access & Opcodes.ACC_STATIC) == (method.access & Opcodes.ACC_STATIC)),
                                name + " exact invoker " + methodName);
                    }
                    AnnotationNode accessor = annotation(method.visibleAnnotations, "/Accessor;");
                    if (accessor == null) accessor = annotation(method.invisibleAnnotations, "/Accessor;");
                    if (accessor != null) {
                        String fieldName = (String) value(accessor, "value");
                        String descriptor = Type.getReturnType(method.desc).getDescriptor();
                        check(owner.fields.stream().anyMatch(f -> f.name.equals(fieldName) && f.desc.equals(descriptor)),
                                name + " accessor " + fieldName);
                    }
                    AnnotationNode injection = annotation(method.visibleAnnotations, "/WrapOperation;");
                    if (injection == null) injection = annotation(method.visibleAnnotations, "/ModifyVariable;");
                    if (injection == null) continue;
                    Object atValue = value(injection, "at");
                    var at = (AnnotationNode) (atValue instanceof List<?> list ? list.getFirst() : atValue);
                    String call = (String) value(at, "target");
                    for (Object selector : (List<?>) value(injection, "method")) {
                        String methodName = (String) selector;
                        var matches = owner.methods.stream().filter(m -> m.name.equals(methodName)).toList();
                        check(matches.size() == 1, name + " target method " + methodName);
                        if (call == null) {
                            check(Type.getArgumentTypes(matches.getFirst().desc)[3].equals(Type.BOOLEAN_TYPE),
                                    name + " boolean argument");
                            continue;
                        }
                        String invocation = call.substring(1, call.indexOf(';')) + "." + call.substring(call.indexOf(';') + 1);
                        int count = 0;
                        for (AbstractInsnNode instruction : matches.getFirst().instructions) {
                            if (instruction instanceof MethodInsnNode m && (m.owner + "." + m.name + m.desc).equals(invocation)) count++;
                        }
                        check(count == 1, name + " invocation " + invocation);
                    }
                }
            }
            ClassNode irisRenderer = read(iris.getInputStream(iris.getEntry("net/irisshaders/iris/mixin/MixinLevelRenderer.class")).readAllBytes());
            check(irisRenderer.fields.stream().anyMatch(f -> f.name.equals("pipeline")
                    && f.desc.equals("Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;")), "Iris pipeline field");
            ClassNode shadows = read(iris.getInputStream(iris.getEntry("net/irisshaders/iris/shadows/ShadowRenderer.class")).readAllBytes());
            check(shadows.fields.stream().anyMatch(f -> f.name.equals("ACTIVE") && f.desc.equals("Z")), "Iris shadow flag");
            ClassNode manager = read(iris.getInputStream(iris.getEntry("net/irisshaders/iris/pipeline/PipelineManager.class")).readAllBytes());
            check(manager.fields.stream().anyMatch(f -> f.name.equals("pipeline")
                    && f.desc.equals("Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;")), "Iris global pipeline field");
            checkCameraDepthTarget(iris, compiled);
            checkCameraColorTarget(iris, compiled);
            checkShaderProgramIsolation(compiled);
            checkSourceEntityTarget(compiled);
        }
        System.out.println("Render compatibility bytecode checks passed: " + checks);
    }

    /** 原版缓存只查询；跨维度隔离须由 Iris 可选登记开启。 */
    private static void checkShaderProgramIsolation(Path compiled) throws Exception {
        String ownerName = "net/minecraft/client/renderer/ShaderInstance";
        ClassNode owner;
        try (var stream = RenderCompatTargetsTest.class.getClassLoader().getResourceAsStream(ownerName + ".class")) {
            check(stream != null, "native ShaderInstance is available for cache isolation checks");
            owner = read(stream.readAllBytes());
        }
        String descriptor = "(Lnet/minecraft/server/packs/resources/ResourceProvider;"
                + "Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;";
        MethodNode target = owner.methods.stream().filter(method -> method.name.equals("getOrCreate")
                && method.desc.equals(descriptor)).findFirst().orElseThrow();
        check(countCalls(target.instructions, "com/mojang/blaze3d/shaders/Program$Type", "getPrograms", "()Ljava/util/Map;") == 1,
                "shader cache redirect has one exact native target");
        check(countCalls(target.instructions, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;") == 1,
                "native shader cache is queried once");
        check(countCalls(target.instructions, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") == 0,
                "empty shader cache view does not replace a native map writer");
        ClassNode mixin = read(Files.readAllBytes(compiled.resolve(
                "com/xfw/shuttershadow/mixin/compat/iris/MixinShaderInstanceForIris.class")));
        var declaration = annotation(mixin.invisibleAnnotations, "/Mixin;");
        check(declaration != null && List.of(Type.getObjectType(ownerName)).equals(value(declaration, "value")),
                "optional shader isolation targets native ShaderInstance");
        var handler = mixin.methods.stream().filter(method -> annotation(method.visibleAnnotations, "/Redirect;") != null)
                .findFirst().orElseThrow();
        var redirect = annotation(handler.visibleAnnotations, "/Redirect;");
        check(List.of("L" + ownerName + ";getOrCreate" + descriptor).equals(value(redirect, "method")),
                "shader isolation retains the exact native descriptor");
        check(countCalls(handler.instructions, "java/util/Collections", "emptyMap", "()Ljava/util/Map;") == 1,
                "Iris program isolation retains the shared immutable empty map");
        check(countCalls(handler.instructions, "com/xfw/shuttershadow/core/ClientWorldLoader", "getIsInitialized", "()Z") == 1,
                "Iris program isolation retains its existing world initialization boundary");
        Path resources = compiled.resolve("../../../../src/main/resources").normalize();
        String ordinaryConfig = Files.readString(resources.resolve("shuttershadow.mixins.json"));
        String compatibilityConfig = Files.readString(resources.resolve("shuttershadow.compat.mixins.json"));
        check(!ordinaryConfig.contains("MixinShaderInstanceForIris"), "shader isolation is absent from unconditional Mixins");
        check(compatibilityConfig.contains("\"iris.MixinShaderInstanceForIris\"")
                        && compatibilityConfig.contains("\"plugin\": \"com.xfw.shuttershadow.compat.CoreCompatMixinPlugin\""),
                "shader isolation is registered behind the Iris availability plugin");
    }

    private static void checkSourceEntityTarget(Path compiled) throws Exception {
        String ownerName = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
        ClassNode owner;
        try (var stream = RenderCompatTargetsTest.class.getClassLoader().getResourceAsStream(ownerName + ".class")) {
            check(stream != null, "native entity dispatcher is available for source capture injection checks");
            owner = read(stream.readAllBytes());
        }
        ClassNode mixin = read(Files.readAllBytes(compiled.resolve(
                "com/xfw/shuttershadow/mixin/minecraft/client/SourceStandEntityRenderMixin.class")));
        var declaration = annotation(mixin.invisibleAnnotations, "/Mixin;");
        check(declaration != null && List.of(Type.getObjectType(ownerName)).equals(value(declaration, "value")),
                "source player hiding targets the actual native entity dispatcher");
        var handler = mixin.methods.stream().filter(method -> annotation(method.visibleAnnotations, "/Inject;") != null)
                .findFirst().orElseThrow();
        var injection = annotation(handler.visibleAnnotations, "/Inject;");
        check(List.of("render").equals(value(injection, "method")), "source hiding has the native render selector");
        String descriptor = handler.desc.replace("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;", "");
        check(owner.methods.stream().anyMatch(method -> method.name.equals("render") && method.desc.equals(descriptor)),
                "source hiding callback matches every native renderer argument");
        var atValue = value(injection, "at");
        var at = (AnnotationNode) (atValue instanceof List<?> list ? list.getFirst() : atValue);
        check("HEAD".equals(value(at, "value")) && Boolean.TRUE.equals(value(injection, "cancellable")),
                "source player render can be stopped before models, names and shadows draw");
    }

    private static void checkCameraColorTarget(ZipFile iris, Path compiled) throws Exception {
        String ownerName = "net/irisshaders/iris/pipeline/FinalPassRenderer";
        ClassNode owner = read(iris.getInputStream(iris.getEntry(ownerName + ".class")).readAllBytes());
        ClassNode mixin = read(Files.readAllBytes(compiled.resolve(
                "com/xfw/shuttershadow/mixin/compat/iris/IrisCameraColorTargetMixin.class")));
        var declaration = annotation(mixin.invisibleAnnotations, "/Mixin;");
        check(declaration != null && List.of(ownerName.replace('/', '.')).equals(value(declaration, "targets")),
                "camera color Mixin targets only Iris FinalPassRenderer");
        check(Boolean.FALSE.equals(value(declaration, "remap")), "Iris color target must not remap");
        check(annotation(mixin.invisibleAnnotations, "/Pseudo;") != null, "Iris color Mixin remains optional");
        for (String name : List.of("lastColorTextureId", "lastColorTextureVersion")) {
            check(mixin.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals("I")
                            && (annotation(f.invisibleAnnotations, "/Shadow;") != null
                            || annotation(f.visibleAnnotations, "/Shadow;") != null))
                            && owner.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals("I")),
                    "Iris exact color integer shadow " + name);
        }
        for (String[] field : new String[][]{{"shuttershadow$cameraColorPending", "Z"},
                {"shuttershadow$cameraTarget", "Lcom/mojang/blaze3d/pipeline/RenderTarget;"}}) {
            check(mixin.fields.stream().anyMatch(f -> f.name.equals(field[0]) && f.desc.equals(field[1])
                            && (f.access & Opcodes.ACC_STATIC) == 0
                            && (annotation(f.invisibleAnnotations, "/Unique;") != null
                            || annotation(f.visibleAnnotations, "/Unique;") != null)),
                    "color lifecycle state remains unique per instance: " + field[0]);
        }
        var nativePass = owner.methods.stream().filter(m -> m.name.equals("renderFinalPass") && m.desc.equals("()V"))
                .findFirst();
        check(nativePass.isPresent(), "Iris exact renderFinalPass descriptor");
        for (String[] hook : new String[][]{{"shuttershadow$checkCameraColor", "HEAD"},
                {"shuttershadow$finishCameraColor", "RETURN"}}) {
            var method = mixin.methods.stream().filter(m -> m.name.equals(hook[0])).findFirst().orElseThrow();
            var inject = annotation(method.visibleAnnotations, "/Inject;");
            check(method.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"),
                    hook[0] + " exact callback descriptor");
            check(inject != null && List.of("renderFinalPass").equals(value(inject, "method")), hook[0] + " exact selector");
            var atValue = value(inject, "at");
            var at = (AnnotationNode) (atValue instanceof List<?> list ? list.getFirst() : atValue);
            check(hook[1].equals(value(at, "value")), hook[0] + " lifecycle position");
            check(!Boolean.TRUE.equals(value(inject, "cancellable")), hook[0] + " preserves native final output");
        }
        var instructions = nativePass.orElseThrow().instructions;
        int versionRead = fieldIndex(instructions, Opcodes.GETFIELD, ownerName, "lastColorTextureVersion", "I");
        int idRead = fieldIndex(instructions, Opcodes.GETFIELD, ownerName, "lastColorTextureId", "I");
        int versionWrite = fieldIndex(instructions, Opcodes.PUTFIELD, ownerName, "lastColorTextureVersion", "I");
        int idWrite = fieldIndex(instructions, Opcodes.PUTFIELD, ownerName, "lastColorTextureId", "I");
        check(versionRead >= 0 && idRead > versionRead && versionWrite > idRead && idWrite > versionWrite,
                "native Iris checks both color values before updating them");
        var versionBranch = nextCode(instructions.get(versionRead));
        check(versionBranch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IF_ICMPNE
                        && instructions.indexOf(jump.label) > idRead && instructions.indexOf(jump.label) < versionWrite,
                "changed native color version enters attachment update");
        var idBranch = nextCode(instructions.get(idRead));
        check(idBranch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IF_ICMPEQ
                        && instructions.indexOf(jump.label) > idWrite,
                "same native color ID and version skip attachment update");
        check(owner.fields.stream().anyMatch(f -> f.name.equals("colorHolder")
                        && f.desc.equals("Lnet/irisshaders/iris/gl/framebuffer/GlFramebuffer;")), "native Iris color holder descriptor");
        check(countCalls(instructions, "net/irisshaders/iris/gl/framebuffer/GlFramebuffer", "addColorAttachment", "(II)V") == 1,
                "native Iris reattaches color holder once");
        String extension = "net/irisshaders/iris/targets/Blaze3dRenderTargetExt";
        ClassNode ext = read(iris.getInputStream(iris.getEntry(extension + ".class")).readAllBytes());
        check(ext.methods.stream().anyMatch(m -> m.name.equals("iris$getColorBufferVersion") && m.desc.equals("()I"))
                        && countCalls(instructions, extension, "iris$getColorBufferVersion", "()I") == 2,
                "typed Iris color version API matches native compare and update");
        ClassNode pipeline = read(iris.getInputStream(iris.getEntry("net/irisshaders/iris/pipeline/IrisRenderingPipeline.class")).readAllBytes());
        var finalize = pipeline.methods.stream().filter(m -> m.name.equals("finalizeLevelRendering") && m.desc.equals("()V"))
                .findFirst().orElseThrow();
        check(countCalls(finalize.instructions, ownerName, "renderFinalPass", "()V") == 1,
                "native pipeline finalization already invokes final color output");
        ClassNode levelMixin = read(iris.getInputStream(iris.getEntry("net/irisshaders/iris/mixin/MixinLevelRenderer.class")).readAllBytes());
        check(levelMixin.methods.stream().anyMatch(m -> countCalls(m.instructions,
                        "net/irisshaders/iris/pipeline/WorldRenderingPipeline", "finalizeLevelRendering", "()V") == 1),
                "ordinary Iris level-render chain already runs pipeline finalization");
    }

    private static void checkCameraDepthTarget(ZipFile iris, Path compiled) throws Exception {
        String ownerName = "net/irisshaders/iris/targets/RenderTargets";
        ClassNode owner = read(iris.getInputStream(iris.getEntry(ownerName + ".class")).readAllBytes());
        ClassNode mixin = read(Files.readAllBytes(compiled.resolve(
                "com/xfw/shuttershadow/mixin/compat/iris/IrisCameraDepthTargetMixin.class")));
        AnnotationNode declaration = annotation(mixin.invisibleAnnotations, "/Mixin;");
        check(declaration != null && List.of(ownerName.replace('/', '.')).equals(value(declaration, "targets")),
                "camera depth Mixin targets only Iris RenderTargets");
        check(Boolean.FALSE.equals(value(declaration, "remap")), "Iris depth target must not remap");
        check(annotation(mixin.invisibleAnnotations, "/Pseudo;") != null, "Iris depth Mixin remains optional");
        for (String name : List.of("currentDepthTexture", "cachedDepthBufferVersion")) {
            check(mixin.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals("I")
                            && (annotation(f.invisibleAnnotations, "/Shadow;") != null
                            || annotation(f.visibleAnnotations, "/Shadow;") != null))
                            && owner.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals("I")),
                    "Iris exact integer shadow " + name);
        }
        check(mixin.fields.stream().anyMatch(f -> f.name.equals("shuttershadow$cameraDepthPending")
                        && f.desc.equals("Z") && (f.access & Opcodes.ACC_STATIC) == 0
                        && (annotation(f.invisibleAnnotations, "/Unique;") != null
                        || annotation(f.visibleAnnotations, "/Unique;") != null)),
                "camera restoration state is unique and per pipeline instance");
        check(mixin.fields.stream().anyMatch(f -> f.name.equals("shuttershadow$cameraTarget")
                        && f.desc.equals("Lcom/mojang/blaze3d/pipeline/RenderTarget;")
                        && (f.access & Opcodes.ACC_STATIC) == 0
                        && (annotation(f.invisibleAnnotations, "/Unique;") != null
                        || annotation(f.visibleAnnotations, "/Unique;") != null)),
                "camera target identity survives recycled texture identifiers per instance");
        String descriptor = "(IIIILnet/irisshaders/iris/gl/texture/DepthBufferFormat;"
                + "Lnet/irisshaders/iris/shaderpack/properties/PackDirectives;)Z";
        var nativeResize = owner.methods.stream().filter(m -> m.name.equals("resizeIfNeeded")
                && m.desc.equals(descriptor)).findFirst();
        check(nativeResize.isPresent(), "Iris exact resizeIfNeeded descriptor");
        String handlerDescriptor = descriptor.substring(0, descriptor.length() - 2)
                + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
        for (String[] hook : new String[][]{{"shuttershadow$checkCameraDepth", "HEAD"},
                {"shuttershadow$finishCameraDepth", "RETURN"}}) {
            var method = mixin.methods.stream().filter(m -> m.name.equals(hook[0])).findFirst().orElseThrow();
            var inject = annotation(method.visibleAnnotations, "/Inject;");
            check(method.desc.equals(handlerDescriptor), hook[0] + " handler matches all native arguments");
            check(inject != null && List.of("resizeIfNeeded").equals(value(inject, "method")),
                    hook[0] + " exact selector");
            var atValue = value(inject, "at");
            var at = (AnnotationNode) (atValue instanceof List<?> list ? list.getFirst() : atValue);
            check(hook[1].equals(value(at, "value")), hook[0] + " lifecycle position");
            check(!Boolean.TRUE.equals(value(inject, "cancellable")), hook[0] + " keeps native resize running");
        }
        var instructions = nativeResize.orElseThrow().instructions;
        int versionRead = fieldIndex(instructions, Opcodes.GETFIELD, ownerName, "cachedDepthBufferVersion", "I");
        int textureWrite = fieldIndex(instructions, Opcodes.PUTFIELD, ownerName, "currentDepthTexture", "I");
        int versionWrite = fieldIndex(instructions, Opcodes.PUTFIELD, ownerName, "cachedDepthBufferVersion", "I");
        check(versionRead >= 0 && textureWrite > versionRead && versionWrite > textureWrite,
                "native Iris version mismatch updates depth texture and cached version");
        var comparison = nextCode(nextCode(instructions.get(versionRead)));
        check(comparison instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IF_ICMPEQ
                        && instructions.indexOf(jump.label) > versionWrite,
                "native depth replacement remains inside version mismatch branch");
        check(previousCode(instructions.get(textureWrite)) instanceof VarInsnNode texture
                        && texture.getOpcode() == Opcodes.ILOAD && texture.var == 2,
                "native replacement uses requested depth texture argument");
        check(countCalls(instructions, "net/irisshaders/iris/gl/framebuffer/GlFramebuffer",
                "addDepthAttachment", "(I)V") == 1, "native Iris reattaches framebuffer depth once per loop");
        check(countCalls(instructions, "net/irisshaders/iris/gl/framebuffer/GlFramebuffer",
                "hasDepthAttachment", "()Z") == 1, "native Iris only reattaches framebuffers with depth");
    }

    private static int fieldIndex(InsnList instructions, int opcode, String owner, String name, String descriptor) {
        for (int i = 0; i < instructions.size(); i++) {
            if (instructions.get(i) instanceof FieldInsnNode f && f.getOpcode() == opcode
                    && f.owner.equals(owner) && f.name.equals(name) && f.desc.equals(descriptor)) return i;
        }
        return -1;
    }

    private static int countCalls(InsnList instructions, String owner, String name, String descriptor) {
        int count = 0;
        for (var instruction : instructions) {
            if (instruction instanceof MethodInsnNode m && m.owner.equals(owner)
                    && m.name.equals(name) && m.desc.equals(descriptor)) count++;
        }
        return count;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static ClassNode read(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String suffix) {
        if (annotations == null) return null;
        return annotations.stream().filter(a -> a.desc.endsWith(suffix)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** Verifies real Exposure targets, photo event order, and native view restoration. */
public final class CameraEnchantmentsTest {
    private static final String CAMERA = "io/github/mortuusars/exposure/world/item/camera/CameraItem";
    private static final String VIEWFINDER = "io/github/mortuusars/exposure/client/camera/viewfinder/Viewfinder";
    private static final String CAMERA_CLIENT = "io/github/mortuusars/exposure/client/camera/CameraClient";
    private static final String SELFIE = "io/github/mortuusars/exposure/client/camera/viewfinder/ViewfinderSelfie";
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path compiled = Path.of(args[1]);
        try (ZipFile exposure = new ZipFile(args[0])) {
            ClassNode camera = nativeClass(exposure, CAMERA);
            ClassNode mixin = compiledClass(compiled, "mixin/exposure/CameraItemRemoteCaptureMixin");
            MethodNode activation = method(camera, "activateInHand");
            check(activation.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;"
                            + "Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;"),
                    "handheld activation must match the exact Exposure descriptor");
            MethodNode selfieHook = method(mixin, "shuttershadow$openInSelfieMode");
            AnnotationNode injection = annotation(selfieHook, "/Inject;");
            check(value(injection, "method").equals(List.of("activateInHand")),
                    "default selfie must be limited to handheld activation");
            check(value(at(injection), "value").equals("HEAD"), "selfie state must be set before the native activation");
            Type[] arguments = Type.getArgumentTypes(activation.desc);
            Type[] hookArguments = Type.getArgumentTypes(selfieHook.desc);
            check(hookArguments.length == arguments.length + 1, "activation hook must keep all original arguments");
            for (int i = 0; i < arguments.length; i++) check(hookArguments[i].equals(arguments[i]), "activation argument " + i);
            check(callCount(selfieHook, "set") == 3, "default selfie must reset both rotation settings with the mode");

            verifyCallHook(camera, mixin, "shuttershadow$commitAfterScreenshot", "/WrapOperation;", "addNewFrame");
            verifyCallHook(camera, mixin, "shuttershadow$mobFilmUploadCallback", "/WrapOperation;", "takePhoto");
            verifyCallHook(camera, mixin, "shuttershadow$discardExposureImage", "/WrapOperation;", "takePhoto");
            AnnotationNode captureArgument = verifyCallHook(camera, mixin, "shuttershadow$standCaptureScene", "/ModifyArg;", "takePhoto");
            check(Integer.valueOf(1).equals(value(captureArgument, "index")), "capture parameters must be the second packet constructor argument");
            MethodNode photo = method(camera, "takePhoto");
            check(callIndex(photo, "addNewFrame") < callIndex(photo, "expect")
                            && callIndex(photo, "expect") < callIndex(photo, "sendToClient"),
                    "frame events must complete before upload authorization and client capture dispatch");
            check(callCount(photo, "sendToClient") == 1,
                    "capture packet cast must apply to the only native client dispatch in takePhoto");
            MethodNode add = method(camera, "addNewFrame");
            check(callCount(add, "onFrameAdded") == 1 && callIndex(add, "addFrameToFilm") < callIndex(add, "onFrameAdded"),
                    "skipping film writing must leave the later native frame event intact");
            MethodNode frameEvent = method(camera, "onFrameAdded");
            check(callCount(frameEvent, "postFrameAddedEvent") == 1 && callCount(frameEvent, "add") == 1,
                    "native frame event and frame history must remain in onFrameAdded");
            check(camera.methods.stream().anyMatch(method -> method.name.startsWith("lambda$onFrameAdded$")
                            && callCount(method, "awardStat") == 1 && callCount(method, "trigger") == 1),
                    "Exposure statistics and advancement triggers must remain in the native frame event");
            check(callCount(method(mixin, "shuttershadow$discardExposureImage"), "completeWithoutUpload") == 1,
                    "discarded image must still commit its mob film transfer without an upload");
            MethodNode mobCompletion = method(compiledClass(compiled, "MobDimensionFilmCapture"), "completeWithoutUpload");
            check(callCount(mobCompletion, "remove") == 1 && callCount(mobCompletion, "transfer") == 1
                            && callIndex(mobCompletion, "remove") < callIndex(mobCompletion, "transfer"),
                    "no-image mob transfer must remove its exact pending record before moving the entity");
            verifyViewfinder(exposure, compiled);
        }
        var config = JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonObject();
        check(config.getAsJsonArray("client").asList().stream()
                        .anyMatch(value -> value.getAsString().equals("exposure.ViewfinderNarcissismMixin")),
                "default-selfie viewfinder hook must be registered only on the client");
        check(config.getAsJsonArray("mixins").asList().stream()
                        .noneMatch(value -> value.getAsString().equals("exposure.ViewfinderNarcissismMixin")),
                "viewfinder hook must not load on a dedicated server");
        System.out.println("Camera enchantment Exposure bytecode checks passed: " + checks);
    }

    private static void verifyViewfinder(ZipFile exposure, Path compiled) throws Exception {
        ClassNode nativeView = nativeClass(exposure, VIEWFINDER);
        MethodNode setup = method(nativeView, "setup");
        MethodNode close = method(nativeView, "close");
        List<Integer> returns = new ArrayList<>();
        int savedPerspective = -1;
        int index = 0;
        for (AbstractInsnNode instruction : setup.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) returns.add(index);
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("cameraTypeBefore")) savedPerspective = index;
            index++;
        }
        check(returns.size() == 2, "native viewfinder setup must have exactly the integration early return and normal return");
        check(savedPerspective > returns.getFirst() && savedPerspective < returns.getLast(),
                "only the final setup return follows native perspective snapshot capture");
        check(close.instructions.iterator().hasNext() && callCount(close, "setCameraType") == 1,
                "native close must restore the original camera type");
        check(fieldCount(close, "cameraTypeBefore", Opcodes.GETFIELD) == 2,
                "native close must test and read the unchanged perspective snapshot");
        ClassNode hookClass = compiledClass(compiled, "mixin/exposure/ViewfinderNarcissismMixin");
        MethodNode hook = method(hookClass, "shuttershadow$defaultSelfie");
        AnnotationNode injection = annotation(hook, "/Inject;");
        check(value(injection, "method").equals(List.of("setup")), "selfie view change must run only at setup");
        AnnotationNode at = at(injection);
        check(value(at, "value").equals("RETURN") && Integer.valueOf(1).equals(value(at, "ordinal")),
                "selfie view change must leave the special integration early return untouched");
        check(hookClass.fields.stream().noneMatch(field -> field.name.equals("cameraTypeBefore"))
                        && fieldCount(hook, "cameraTypeBefore", Opcodes.PUTFIELD) == 0,
                "selfie hook must preserve the snapshot used by native close");
        check(callCount(hook, "setCameraType") == 1 && callCount(hook, "updateSelfieMode") == 1
                        && callIndex(hook, "setCameraType") < callIndex(hook, "updateSelfieMode"),
                "native selfie update must observe the already-updated client perspective");
        check(fieldCount(hook, "THIRD_PERSON_FRONT", Opcodes.GETSTATIC) == 1,
                "default selfie must use the exact perspective recognized by Exposure");
        check(hook.instructions.iterator().hasNext() && callCount(hook, "viewfinder") == 1
                        && java.util.stream.StreamSupport.stream(hook.instructions.spliterator(), false)
                        .anyMatch(instruction -> instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF
                                && type.desc.equals("io/github/mortuusars/exposure/world/camera/CameraInHand")),
                "view change must be restricted to the active handheld camera");
        MethodNode updateSelfie = method(nativeClass(exposure, SELFIE), "updateSelfieMode");
        check(fieldCount(updateSelfie, "THIRD_PERSON_FRONT", Opcodes.GETSTATIC) == 1
                        && callCount(updateSelfie, "setAndSync") == 3,
                "native selfie update must synchronize mode and rotations from the chosen perspective");
        MethodNode nativeTick = method(nativeView, "tick");
        check(nativeTick.desc.equals("()V") && callCount(nativeTick, "tick") == 1
                        && callCount(method(nativeClass(exposure, SELFIE), "tick"), "updateSelfieMode") == 1,
                "tick HEAD must run before the native automatic selfie-mode write");
        MethodNode cleanup = method(hookClass, "shuttershadow$closeDetachedHandheld");
        AnnotationNode tickInjection = annotation(cleanup, "/Inject;");
        check(value(tickInjection, "method").equals(List.of("tick"))
                        && value(at(tickInjection), "value").equals("HEAD")
                        && Boolean.TRUE.equals(value(tickInjection, "cancellable")),
                "stale handheld cleanup must cancel the native tick before shader and selfie updates");
        check(callCount(cleanup, "getId") == 1 && callCount(cleanup, "matches") == 1
                        && callIndex(cleanup, "matches") < callIndex(cleanup, "deactivate"),
                "cleanup must validate the fixed CameraId against the current hand stack");
        check(callCount(cleanup, "isActive") == 0,
                "identity cleanup must leave transient activation and slot synchronization state untouched");
        check(callIndex(cleanup, "deactivate") < callIndex(cleanup, "removeActiveExposureCamera")
                        && callIndex(cleanup, "removeActiveExposureCamera") < callIndex(cleanup, "cancel"),
                "cleanup must deactivate the original camera before removing its viewfinder and cancelling");
        check(callCount(cleanup, "sendToServer") == 0 && callCount(cleanup, "set") == 0
                        && callCount(cleanup, "setAndSync") == 0,
                "cleanup must neither dispatch a generic deactivate packet nor write replacement settings");
        MethodNode nativeDeactivate = method(nativeClass(exposure,
                "io/github/mortuusars/exposure/world/camera/CameraInHand"), "deactivate");
        check(callCount(nativeDeactivate, "matches") == 2 && callCount(nativeDeactivate, "getInventory") == 1,
                "native hand deactivation must locate the original camera by ID in the opposite hand or inventory");
        check(callCount(close, "setAndSync") == 0 && callCount(close, "sendToServer") == 0,
                "native viewfinder close must not write settings onto the replacement stack");
        MethodNode clientSetup = method(nativeClass(exposure, CAMERA_CLIENT), "setupViewfinder");
        int assigned = -1;
        index = 0;
        for (AbstractInsnNode instruction : clientSetup.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.name.equals("activeViewfinder")) assigned = index;
            index++;
        }
        check(assigned >= 0 && assigned < callIndex(clientSetup, "setup"),
                "the active-instance guard must be valid when native setup reaches the hook");
    }

    private static AnnotationNode verifyCallHook(ClassNode owner, ClassNode mixin, String name, String kind, String selector) {
        AnnotationNode injection = annotation(method(mixin, name), kind);
        check(value(injection, "method").equals(List.of(selector)), name + " must select the exact native enclosing method");
        String target = (String) value(at(injection), "target");
        String callOwner = target.substring(1, target.indexOf(';'));
        String call = target.substring(target.indexOf(';') + 1);
        int count = 0;
        for (AbstractInsnNode instruction : method(owner, selector).instructions) {
            if (instruction instanceof MethodInsnNode invoked && invoked.owner.equals(callOwner)
                    && (invoked.name + invoked.desc).equals(call)) count++;
        }
        check(count == 1, name + " must have exactly one actual Exposure invocation with the full descriptor");
        return injection;
    }
    private static AnnotationNode annotation(MethodNode method, String suffix) {
        return method.visibleAnnotations.stream().filter(annotation -> annotation.desc.endsWith(suffix)).findFirst().orElseThrow();
    }
    private static AnnotationNode at(AnnotationNode annotation) {
        Object value = value(annotation, "at");
        return (AnnotationNode) (value instanceof List<?> list ? list.getFirst() : value);
    }
    private static Object value(AnnotationNode annotation, String key) {
        int index = annotation.values.indexOf(key);
        return index < 0 ? null : annotation.values.get(index + 1);
    }
    private static MethodNode method(ClassNode owner, String name) {
        List<MethodNode> matches = owner.methods.stream().filter(method -> method.name.equals(name)).toList();
        check(matches.size() == 1, owner.name + " exact method " + name);
        return matches.getFirst();
    }
    private static int callCount(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals(name)) count++;
        return count;
    }
    private static int callIndex(MethodNode method, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return index;
            index++;
        }
        return -1;
    }
    private static int fieldCount(MethodNode method, String name, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof FieldInsnNode field
                && field.name.equals(name) && field.getOpcode() == opcode) count++;
        return count;
    }
    private static ClassNode compiledClass(Path directory, String path) throws Exception {
        return read(Files.readAllBytes(directory.resolve("com/xfw/shuttershadow/" + path + ".class")));
    }
    private static ClassNode nativeClass(ZipFile zip, String path) throws Exception {
        return read(zip.getInputStream(zip.getEntry(path + ".class")).readAllBytes());
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
}

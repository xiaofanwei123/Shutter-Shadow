import java.util.*;
import java.util.function.BiConsumer;

public class CameraEnchantmentBehaviorTest {
    private static int assertions;
    private static final CameraHooks HOOKS = new CameraHooks();
    private static final ServerPlayer PLAYER = new ServerPlayer(UUID.randomUUID());
    private static final Frame FRAME = new Frame();
    private static final ResourceKey<Enchantment> FAILURE = CameraEnchantments.EXPOSURE_FAILURE;
    private static final ResourceKey<Enchantment> NARCISSISM = CameraEnchantments.NARCISSISM;

    public static void main(String[] args) {
        actualCameraEnchantmentsOnly();
        activationDefaults();
        captureHooks();
        exactPendingIdentity();
        pendingTransferCleanup();
        clientViewfinderScope();
        handheldSlotSwitch();
        System.out.println("Camera curses: 7 scenario groups / " + assertions + " assertions passed");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static ItemStack camera(ResourceKey<Enchantment> key, int level) {
        ItemStack stack = new ItemStack(new CameraItem());
        stack.values.put(DataComponents.ENCHANTMENTS, new ItemEnchantments(key, level));
        return stack;
    }

    private static void actualCameraEnchantmentsOnly() {
        ItemStack ordinary = new ItemStack(new Item());
        ordinary.values.put(DataComponents.ENCHANTMENTS, new ItemEnchantments(FAILURE, 1));
        check(!CameraEnchantments.has(ordinary, FAILURE), "ordinary items cannot trigger camera curses");
        ItemStack book = new ItemStack(new CameraItem());
        book.values.put(DataComponents.STORED_ENCHANTMENTS, new ItemEnchantments(FAILURE, 1));
        check(!CameraEnchantments.has(book, FAILURE), "stored book enchantments must not enable the effect");
        check(!CameraEnchantments.has(new ItemStack(new CameraItem()), FAILURE), "unenchantmented cameras stay native");
        check(!CameraEnchantments.has(camera(FAILURE, 0), FAILURE), "zero level is inactive");
        check(!CameraEnchantments.has(camera(FAILURE, -1), FAILURE), "negative level is inactive");
        check(CameraEnchantments.has(camera(FAILURE, 1), FAILURE), "positive actual enchantment is active");
        check(!CameraEnchantments.has(camera(FAILURE, 1), NARCISSISM), "different curse key does not match");
    }

    private static void activationDefaults() {
        ItemStack cursed = camera(NARCISSISM, 1);
        CameraSettings.SELFIE_ROTATION_X.set(cursed, 12.0);
        CameraSettings.SELFIE_ROTATION_Y.set(cursed, -8.0);
        HOOKS.shuttershadow$openInSelfieMode(PLAYER, cursed, InteractionHand.MAIN_HAND, new CallbackInfoReturnable<>());
        check(Boolean.TRUE.equals(cursed.values.get("selfie")), "handheld opening defaults to selfie");
        check(cursed.values.get("x").equals(0.0) && cursed.values.get("y").equals(0.0), "selfie opening resets both rotations");
        CameraSettings.SELFIE_MODE.set(cursed, false);
        HOOKS.shuttershadow$openInSelfieMode(PLAYER, cursed, InteractionHand.OFF_HAND, new CallbackInfoReturnable<>());
        check(Boolean.TRUE.equals(cursed.values.get("selfie")), "reopening either hand restores the default");
        ItemStack ordinary = camera(FAILURE, 1);
        ordinary.values.put("x", 9.0);
        HOOKS.shuttershadow$openInSelfieMode(PLAYER, ordinary, InteractionHand.MAIN_HAND, new CallbackInfoReturnable<>());
        check(!ordinary.values.containsKey("selfie") && ordinary.values.get("x").equals(9.0), "other cameras keep their mode and rotation");
    }

    private static void captureHooks() {
        ItemStack ordinary = camera(NARCISSISM, 1);
        Operation<Void> commit = new Operation<>();
        RemoteStandPreparation.defer = false;
        HOOKS.shuttershadow$commitAfterScreenshot((CameraItem) ordinary.getItem(), ordinary, FRAME, commit);
        check(commit.calls == 1 && commit.arguments[1] == ordinary && commit.arguments[2] == FRAME,
                "ordinary frame commit forwards unchanged arguments");
        RemoteStandPreparation.defer = true;
        HOOKS.shuttershadow$commitAfterScreenshot((CameraItem) ordinary.getItem(), ordinary, FRAME, commit);
        check(commit.calls == 1, "prepared ordinary capture still defers frame commit");
        ItemStack failed = camera(FAILURE, 1);
        int deferrals = RemoteStandPreparation.calls;
        HOOKS.shuttershadow$commitAfterScreenshot((CameraItem) failed.getItem(), failed, FRAME, commit);
        check(commit.calls == 1 && RemoteStandPreparation.calls == deferrals, "exposure failure skips frame commit and deferral");
        ExposureRepository repository = new ExposureRepository();
        Operation<Void> expect = new Operation<>();
        HOOKS.shuttershadow$mobFilmUploadCallback(repository, PLAYER, "normal", expect, ordinary);
        check(expect.calls == 1 && expect.arguments[0] == repository && expect.arguments[2].equals("normal"),
                "ordinary non-creature photo keeps native upload expectation");
        var pending = pending(PLAYER, "creature", new LivingEntity(), false);
        HOOKS.shuttershadow$mobFilmUploadCallback(repository, PLAYER, "creature", expect, ordinary);
        check(expect.calls == 1 && repository.callback != null, "ordinary creature capture retains its upload callback");
        var previous = repository.callback;
        HOOKS.shuttershadow$mobFilmUploadCallback(repository, PLAYER, "failed", expect, failed);
        check(expect.calls == 1 && repository.callback == previous, "failed exposure creates no expectation");
        Operation<Void> send = new Operation<>();
        CaptureStartS2CP packet = new CaptureStartS2CP(new CaptureParameters("normal"));
        HOOKS.shuttershadow$discardExposureImage(packet, PLAYER, send, ordinary);
        check(send.calls == 1 && send.arguments[0] == packet && send.arguments[1] == PLAYER,
                "ordinary capture sends the native packet unchanged");
        HOOKS.shuttershadow$discardExposureImage(new CaptureStartS2CP(new CaptureParameters("creature")), PLAYER, send, failed);
        check(send.calls == 1 && !MobDimensionFilmCapture.PENDING.containsValue(pending),
                "failed creature exposure skips rendering and commits the selected candidate");
    }

    private static MobDimensionFilmCapture.Pending pending(ServerPlayer photographer, String id, Entity entity, boolean clear) {
        if (clear) {
            MobDimensionFilmCapture.PENDING.clear();
            ChunkLoading.releases = 0;
            SeamlessTeleportation.calls = 0;
            SeamlessTeleportation.failure = false;
            SeamlessTeleportation.result = 0;
        }
        ServerLevel source = new ServerLevel(), target = new ServerLevel();
        UUID entityId = UUID.randomUUID();
        if (entity != null) { target.entities.put(entityId, entity); entity.world = target; }
        var pending = new MobDimensionFilmCapture.Pending(photographer, source, target, entityId);
        MobDimensionFilmCapture.PENDING.put(new MobDimensionFilmCapture.PendingKey(photographer.getUUID(), id), pending);
        return pending;
    }

    private static void exactPendingIdentity() {
        var candidate = pending(PLAYER, "chosen", new LivingEntity(), true);
        MobDimensionFilmCapture.completeWithoutUpload(PLAYER, "wrong");
        check(MobDimensionFilmCapture.PENDING.containsValue(candidate) && SeamlessTeleportation.calls == 0,
                "a different frame cannot consume the candidate");
        MobDimensionFilmCapture.completeWithoutUpload(new ServerPlayer(UUID.randomUUID()), "chosen");
        check(MobDimensionFilmCapture.PENDING.containsValue(candidate), "a different uploader cannot consume the candidate");
        MobDimensionFilmCapture.completeWithoutUpload(new ServerPlayer(PLAYER.getUUID()), "chosen");
        check(MobDimensionFilmCapture.PENDING.containsValue(candidate), "reused UUID on a different connection cannot consume the candidate");
        MobDimensionFilmCapture.completeWithoutUpload(PLAYER, "chosen");
        check(SeamlessTeleportation.calls == 1 && ChunkLoading.releases == 1
                && MobDimensionFilmCapture.PENDING.isEmpty(), "matching shot transfers once and releases its ticket");
        MobDimensionFilmCapture.completeWithoutUpload(PLAYER, "chosen");
        check(SeamlessTeleportation.calls == 1 && ChunkLoading.releases == 1, "repeat completion is a no-op");
        var awaiting = pending(PLAYER, "waiting", new LivingEntity(), true);
        ExposureRepository repository = new ExposureRepository();
        MobDimensionFilmCapture.expectUpload(repository, PLAYER, "waiting");
        MobDimensionFilmCapture.completeWithoutUpload(PLAYER, "waiting");
        repository.callback.accept(PLAYER, "waiting");
        check(!MobDimensionFilmCapture.PENDING.containsValue(awaiting) && SeamlessTeleportation.calls == 1
                && ChunkLoading.releases == 1, "late upload callback cannot transfer an already completed candidate");
    }

    private static void pendingTransferCleanup() {
        for (int state = 0; state < 9; state++) {
            Entity selected = switch (state) {
                case 0 -> null;
                case 1 -> new Entity();
                case 2 -> new Player();
                default -> new LivingEntity();
            };
            if (selected != null) { selected.removed = state == 4; selected.alive = state != 3; }
            pending(PLAYER, "test", selected, true);
            SeamlessTeleportation.failure = state == 5;
            SeamlessTeleportation.result = Math.max(0, state - 5);
            MobDimensionFilmCapture.completeWithoutUpload(PLAYER, "test");
            check(ChunkLoading.releases == 1 && MobDimensionFilmCapture.PENDING.isEmpty(),
                    "invalid/missing/removed entity or failed teleport must release the ticket");
            check(SeamlessTeleportation.calls == (state >= 5 ? 1 : 0),
                    "only a live non-player creature reaches teleportation");
        }
    }

    private static void clientViewfinderScope() {
        ItemStack cursed = camera(NARCISSISM, 1);
        ClientViewfinder current = new ClientViewfinder(new CameraInHand(cursed));
        Minecraft client = Minecraft.getInstance();
        client.options.setCameraType(CameraType.FIRST_PERSON);
        CameraClient.active = current;
        current.shuttershadow$defaultSelfie(new CallbackInfo());
        check(client.options.type == CameraType.THIRD_PERSON_FRONT && current.selfie.calls == 1,
                "current cursed handheld viewfinder sets the real camera mode and updates native selfie");
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        CameraClient.active = new Object();
        current.shuttershadow$defaultSelfie(new CallbackInfo());
        check(client.options.type == CameraType.THIRD_PERSON_BACK && current.selfie.calls == 1,
                "inactive viewfinders cannot change the player's view");
        for (Camera camera : List.of(new CameraOnStand(cursed), new CameraInHand(camera(FAILURE, 1)))) {
            ClientViewfinder excluded = new ClientViewfinder(camera);
            CameraClient.active = excluded;
            excluded.shuttershadow$defaultSelfie(new CallbackInfo());
            check(client.options.type == CameraType.THIRD_PERSON_BACK && excluded.selfie.calls == 0,
                    "stand and uncursed viewfinders keep native behavior");
        }
    }

    private static void handheldSlotSwitch() {
        Minecraft client = Minecraft.getInstance();
        client.player = new ClientPlayer();
        for (boolean cursed : List.of(false, true)) {
            ItemStack original = camera(cursed ? NARCISSISM : FAILURE, 1);
            CameraInHand oldCamera = new CameraInHand(original);
            client.options.setCameraType(CameraType.FIRST_PERSON);
            ClientViewfinder oldViewfinder = new ClientViewfinder(oldCamera);
            CameraClient.active = oldViewfinder;
            client.player.active = oldCamera;
            client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            oldViewfinder.selfie.updateSelfieMode();
            check(Boolean.TRUE.equals(original.values.get("selfie")), "old camera starts in real selfie mode");
            ItemStack replacement = camera(FAILURE, 1);
            replacement.values.put("x", 14.0);
            oldCamera.stack = replacement;

            // The native tick writes the replacement stack despite an unchanged old CameraId.
            oldViewfinder.selfie.updateSelfieMode();
            check(Boolean.TRUE.equals(replacement.values.get("selfie")),
                    "native dynamic-hand selfie reproduces the wooden-stick bug after switching cameras");
            replacement.values.remove("selfie");
            int updatesBefore = oldViewfinder.selfie.calls;
            CallbackInfo tick = oldViewfinder.tick();
            check(tick.cancelled && CameraClient.active == null && client.player.active == null,
                    "switching cameras removes the stale raw camera and cancels its native tick");
            check(client.options.type == CameraType.FIRST_PERSON && !oldViewfinder.shaderActive,
                    "closing stale viewfinder restores the original perspective and releases postprocessing");
            check(oldViewfinder.selfie.calls == updatesBefore && oldViewfinder.shaderUpdates == 0,
                    "stale camera cannot update settings or shader on the replacement item");
            check(!replacement.values.containsKey("selfie") && replacement.values.get("x").equals(14.0),
                    "slot switch must leave the replacement camera's own settings unchanged");
            check(Boolean.FALSE.equals(original.values.get("active")) && Boolean.FALSE.equals(original.values.get("selfie")),
                    "native ID-based deactivation clears only the original camera still in inventory");
        }

        CameraInHand valid = new CameraInHand(camera(NARCISSISM, 1));
        client.options.setCameraType(CameraType.FIRST_PERSON);
        ClientViewfinder validViewfinder = new ClientViewfinder(valid);
        CameraClient.active = validViewfinder;
        client.player.active = valid;
        check(!validViewfinder.tick().cancelled && CameraClient.active == validViewfinder
                        && validViewfinder.shaderUpdates == 1,
                "matching active handheld camera keeps native viewfinder ticking");
        valid.stack.values.put("active", false);
        int removalsBeforeInactive = client.player.removes;
        check(!validViewfinder.tick().cancelled && CameraClient.active == validViewfinder
                        && client.player.removes == removalsBeforeInactive,
                "matching camera is not closed when activation or slot synchronization briefly reports inactive");

        CameraOnStand stand = new CameraOnStand(camera(NARCISSISM, 1));
        ClientViewfinder standViewfinder = new ClientViewfinder(stand);
        CameraClient.active = standViewfinder;
        client.player.active = stand;
        stand.stack.values.put("active", false);
        check(!standViewfinder.tick().cancelled && CameraClient.active == standViewfinder,
                "stand lifecycle remains owned by its existing stand-specific cleanup");

        ClientViewfinder old = new ClientViewfinder(new CameraInHand(camera(FAILURE, 1)));
        old.camera.stack = new ItemStack(new Item());
        int removalsBefore = client.player.removes;
        check(!old.tick().cancelled && CameraClient.active == standViewfinder && client.player.removes == removalsBefore,
                "a superseded viewfinder cannot clear a newly activated one");

        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        CameraInHand swappedToItem = new CameraInHand(camera(FAILURE, 1));
        ClientViewfinder thirdPerson = new ClientViewfinder(swappedToItem);
        CameraClient.active = thirdPerson;
        client.player.active = swappedToItem;
        client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        swappedToItem.stack = new ItemStack(new Item());
        check(thirdPerson.tick().cancelled && client.options.type == CameraType.THIRD_PERSON_BACK,
                "switching to a noncamera restores the user's original third-person view");
    }
}

record ResourceLocation(String namespace, String path) {
    static ResourceLocation fromNamespaceAndPath(String namespace, String path) { return new ResourceLocation(namespace, path); }
    ResourceLocation location() { return this; }
}
record ResourceKey<T>(ResourceLocation location) {
    static <T> ResourceKey<T> create(Object registry, ResourceLocation location) { return new ResourceKey<>(location); }
}
record Enchantment() {}
record Holder(ResourceKey<Enchantment> key) { boolean is(ResourceKey<Enchantment> other) { return key.equals(other); } }
record EnchantmentEntry(Holder getKey, int getIntValue) {}
final class ItemEnchantments {
    static final ItemEnchantments EMPTY = new ItemEnchantments(null, 0);
    final List<EnchantmentEntry> entries;
    ItemEnchantments(ResourceKey<Enchantment> key, int value) {
        entries = key == null ? List.of() : List.of(new EnchantmentEntry(new Holder(key), value));
    }
    List<EnchantmentEntry> entrySet() { return entries; }
}
class Item {}
final class CameraItem extends Item {}
final class ItemStack {
    final Item item;
    final Map<Object, Object> values = new HashMap<>();
    ItemStack(Item item) { this.item = item; }
    Item getItem() { return item; }
    @SuppressWarnings("unchecked") <T> T getOrDefault(Object key, T fallback) { return (T) values.getOrDefault(key, fallback); }
}
final class DataComponents {
    static final Object ENCHANTMENTS = new Object(), STORED_ENCHANTMENTS = new Object();
}
final class Registries { static final Object ENCHANTMENT = new Object(); }
final class CameraSettings {
    static final Setting SELFIE_MODE = new Setting("selfie"), SELFIE_ROTATION_X = new Setting("x"), SELFIE_ROTATION_Y = new Setting("y");
    record Setting(String key) { void set(ItemStack camera, Object value) { camera.values.put(key, value); } }
}
enum InteractionHand { MAIN_HAND, OFF_HAND }
record InteractionResultHolder<T>(T value) {}
class CallbackInfo { boolean cancelled; void cancel() { cancelled = true; } }
class CallbackInfoReturnable<T> extends CallbackInfo {}
final class Operation<T> {
    int calls;
    Object[] arguments;
    T call(Object... arguments) { calls++; this.arguments = arguments; return null; }
}
final class Frame {}
final class RemoteStandPreparation {
    static boolean defer;
    static int calls;
    static boolean deferFrameCommit(ItemStack camera, Frame frame) { calls++; return defer; }
}
interface Packet {}
record CaptureParameters(String exposureId) {}
record CaptureStartS2CP(CaptureParameters captureParameters) implements Packet {}
final class ExposureRepository {
    BiConsumer<ServerPlayer, String> callback;
    void expect(ServerPlayer player, String id, BiConsumer<ServerPlayer, String> callback) { this.callback = callback; }
}
class Entity {
    boolean alive = true, removed;
    ServerLevel world;
    boolean isRemoved() { return removed; }
    ServerLevel level() { return world; }
}
class LivingEntity extends Entity { boolean isAlive() { return alive; } }
class Player extends LivingEntity {}
final class ServerPlayer extends Player {
    final UUID uuid;
    ServerPlayer(UUID uuid) { this.uuid = uuid; }
    UUID getUUID() { return uuid; }
    Object getServer() { return this; }
}
final class ServerLevel {
    final Map<UUID, Entity> entities = new HashMap<>();
    Entity getEntity(UUID id) { return entities.get(id); }
    ResourceLocation dimension() { return new ResourceLocation("test", "dimension"); }
}
final class Vec3 {}
final class ChunkLoader {}
final class ChunkLoading {
    static int releases;
    static void removeGlobalChunkLoader(Object server, ChunkLoader loader) { releases++; }
}
final class SeamlessTeleportation {
    static int calls;
    static boolean failure;
    static int result;
    static Entity teleportEntity(Entity entity, ServerLevel source, Vec3 destination) {
        calls++;
        if (failure) throw new IllegalArgumentException("failed teleport");
        if (result == 1) return null;
        if (result == 2) entity.removed = true;
        if (result != 3) entity.world = source;
        return entity;
    }
}
final class Shuttershadow {
    static final String MODID = "shuttershadow";
    static final Logger LOGGER = new Logger();
    static final class Logger {
        void warn(String message, Object... args) {}
        void error(String message, Object... args) {}
    }
}
record CameraId(UUID id) { boolean matches(ItemStack stack) { return id.equals(stack.values.get("cameraId")); } }
class Camera {
    ItemStack stack;
    final ItemStack original;
    final CameraId id;
    Camera(ItemStack stack) {
        this.stack = stack; this.original = stack;
        this.id = new CameraId((UUID) stack.values.computeIfAbsent("cameraId", unused -> UUID.randomUUID()));
        stack.values.put("active", true);
    }
    ItemStack getItemStack() { return stack; }
    CameraId getId() { return id; }
    boolean isActive() { return Boolean.TRUE.equals(stack.values.get("active")); }
    boolean deactivate() {
        ItemStack matching = id.matches(stack) ? stack : original;
        if (!id.matches(matching) || !Boolean.TRUE.equals(matching.values.get("active"))) return false;
        matching.values.put("active", false); matching.values.put("selfie", false);
        return true;
    }
}
final class CameraInHand extends Camera { CameraInHand(ItemStack stack) { super(stack); } }
final class CameraOnStand extends Camera { CameraOnStand(ItemStack stack) { super(stack); } }
interface CameraOperator { void removeActiveExposureCamera(); }
final class ClientPlayer extends Player implements CameraOperator {
    Camera active;
    int removes;
    public void removeActiveExposureCamera() { removes++; active = null; CameraClient.removeViewfinder(); }
}
final class CameraClient {
    static Object active;
    static Object viewfinder() { return active; }
    static void removeViewfinder() {
        if (active instanceof ClientViewfinder viewfinder) viewfinder.close();
        active = null;
    }
}
final class ViewfinderSelfie {
    final Camera camera;
    int calls;
    ViewfinderSelfie(Camera camera) { this.camera = camera; }
    void updateSelfieMode() {
        calls++;
        if (camera.getItemStack().getItem() instanceof CameraItem) {
            camera.getItemStack().values.put("selfie",
                    Minecraft.getInstance().options.type == CameraType.THIRD_PERSON_FRONT);
        }
    }
}
final class ClientViewfinder extends ClientHooks {
    final ViewfinderSelfie selfie;
    final CameraType cameraTypeBefore;
    boolean shaderActive = true;
    int shaderUpdates;
    ClientViewfinder(Camera camera) {
        this.camera = camera; this.selfie = new ViewfinderSelfie(camera);
        this.cameraTypeBefore = Minecraft.getInstance().options.type;
    }
    public ViewfinderSelfie selfie() { return selfie; }
    CallbackInfo tick() {
        CallbackInfo callback = new CallbackInfo();
        shuttershadow$closeDetachedHandheld(callback);
        if (!callback.cancelled) { shaderUpdates++; selfie.updateSelfieMode(); }
        return callback;
    }
    void close() { Minecraft.getInstance().options.setCameraType(cameraTypeBefore); shaderActive = false; }
}
enum CameraType { FIRST_PERSON, THIRD_PERSON_BACK, THIRD_PERSON_FRONT }
final class Minecraft {
    static final Minecraft INSTANCE = new Minecraft();
    final Options options = new Options();
    ClientPlayer player;
    static Minecraft getInstance() { return INSTANCE; }
    static final class Options { CameraType type; void setCameraType(CameraType type) { this.type = type; } }
}

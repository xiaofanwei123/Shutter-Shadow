import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.RemoteCaptureContext;
import com.xfw.shuttershadow.ShuttershadowConfig;
import com.xfw.shuttershadow.client.ImmersiveCameraClient;
import com.xfw.shuttershadow.network.CameraSessionRequestC2S;
import com.xfw.shuttershadow.network.RemoteCameraSession;
import com.xfw.shuttershadow.network.RemoteSceneStartS2C;
import com.xfw.shuttershadow.api.ChunkLoading;
import com.xfw.shuttershadow.api.ChunkLoader;
import com.xfw.shuttershadow.core.chunk_loading.RemoteChunkTracking;
import com.xfw.shuttershadow.util.McHelper;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;

/** 共用物品 ID 的不同维度滤镜不能共享旧路由；执行生产服务端与客户端判断。 */
public final class CameraRouteIdentityTest {
    private static final ResourceLocation SOURCE = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation END = ResourceLocation.parse("minecraft:the_end");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static final ResourceLocation FILTER = ResourceLocation.parse("shuttershadow:dimension_filter");
    private static final Item SHARED_FILTER = new Item(FILTER);
    private static final CameraItem CAMERA = new CameraItem(ResourceLocation.parse("exposure:camera"));
    private static int checks;

    private static final class Environment {
        final MinecraftServer server = new MinecraftServer();
        final ServerLevel source = new ServerLevel(key(SOURCE), 1);
        final ServerLevel end = new ServerLevel(key(END), 1);
        final ServerLevel nether = new ServerLevel(key(NETHER), 8);
        final ServerPlayer player = new ServerPlayer(1, UUID.randomUUID(), server, source);

        Environment() {
            server.levels.put(source.dimension(), source);
            server.levels.put(end.dimension(), end);
            server.levels.put(nether.dimension(), nether);
            server.players.players.put(player.getUUID(), player);
            player.setPos(new Vec3(80, 70, -40));
            route(END, 1); route(NETHER, 8);
        }

        DimensionFilters.Route route(ResourceLocation target, double scale) {
            var entry = new DimensionFilters.Route(FILTER, target, scale);
            DimensionFilters.routes.put(new DimensionFilters.Key(target, SOURCE), entry);
            return entry;
        }

        CameraStandEntity stand(int id, ResourceLocation target) {
            var stand = new CameraStandEntity(id, source, player, camera(target));
            stand.setPos(new Vec3(id * 16, 65, -id * 8)); source.entities.put(id, stand);
            return stand;
        }

        RemoteCameraSession open(long sequence, ResourceLocation target, int standId) {
            RemoteCameraSession.handle(new CameraSessionRequestC2S(sequence, FILTER, target, standId), player);
            return RemoteCameraSession.active(player.getUUID());
        }
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "shared-filter-target" -> sharedFilterTarget();
            case "scale-change" -> scaleChange();
            case "valid-session" -> validSession();
            case "multiple-stands" -> multipleStands();
            case "snapshot-route" -> snapshotRoute();
            case "owner-replaced" -> ownerReplaced();
            case "invalid-route" -> invalidRoute();
            case "expected-hand-target" -> expectedHandTarget();
            case "delayed-components" -> delayedComponents();
            case "request-codec" -> requestCodec();
            case "client-start" -> clientStart();
            case "client-retry" -> clientRetry();
            case "capture-current-window" -> captureCurrentWindow();
            case "view-limit-default" -> viewLimitDefault();
            case "view-limit-handheld" -> viewLimitHandheld();
            case "view-limit-stand" -> viewLimitStand();
            case "view-limit-client-server" -> viewLimitClientServer();
            case "view-limit-live" -> viewLimitLive();
            case "view-limit-capture" -> viewLimitCapture();
            default -> throw new AssertionError(args[0]);
        }
        System.out.println("PASS: " + args[0] + " (" + checks + " checks)");
    }

    private static void sharedFilterTarget() throws Exception {
        var env = new Environment();
        var stand = env.stand(10, NETHER);
        var old = env.open(1, NETHER, stand.getId());
        check(old != null && valid(old), "original Nether session is valid");
        stand.camera = camera(END);
        check(stand.camera.filter.getItem() == SHARED_FILTER, "changed End filter still has the identical shared Item object");
        check(!valid(old) && RemoteCameraSession.active(env.player.getUUID()) == null,
                "switching target components invalidates old Nether session immediately");
        var current = RemoteCaptureContext.resolveForTransfer(stand, stand.camera);
        check(current != null && current.level() == env.end && current.coordinateScale() == 1,
                "stale stand session falls back to the current End route");
        check(current.asHolderEntity().position().equals(stand.position()), "End fallback uses current stand coordinates");
        var replacement = env.open(2, END, stand.getId());
        check(replacement != null && replacement != old && replacement.remoteLevel() == env.end, "new request installs End session under same UUID");
    }

    private static void scaleChange() throws Exception {
        var env = new Environment(); var stand = env.stand(10, NETHER);
        var old = env.open(42, NETHER, stand.getId());
        env.route(NETHER, 4);
        check(!valid(old), "same-target route-scale edit invalidates cached mapping");
        var fallback = RemoteCaptureContext.resolveForTransfer(stand, stand.camera);
        check(fallback != null && fallback.coordinateScale() == 0.25, "stand fallback computes fresh horizontal scale");
        check(fallback.asHolderEntity().position().equals(new Vec3(40, 65, -20)), "fresh mapping affects X/Z and preserves Y");
        var current = env.open(42, NETHER, stand.getId());
        check(current != null && current != old && current.coordinateScale() == 0.25, "same sequence retry rebuilds a session after scale changes");
    }

    private static void validSession() throws Exception {
        var env = new Environment(); var stand = env.stand(10, END);
        var current = env.open(1, END, stand.getId());
        check(current != null && valid(current), "same target has a live session");
        env.route(END, 1);
        check(valid(current), "equal immutable mapping replacement preserves session");
        var frozenOrigin = current.sourceOrigin();
        stand.setPos(new Vec3(300, 80, 90));
        var context = RemoteCaptureContext.resolveForTransfer(stand, stand.camera);
        check(context != null && context.level() == env.end && context.asHolderEntity().position().equals(frozenOrigin),
                "unchanged valid stand session retains its existing camera origin");
        int before = PacketDistributor.scenes.size();
        check(env.open(1, END, stand.getId()) == current, "valid same-sequence retries keep the loader-owning session");
        check(PacketDistributor.scenes.size() == before + 1, "valid repeated request resends acknowledgement scene");
        env.player.main = camera(NETHER);
        var handheld = env.open(2, NETHER, -1);
        env.player.setPos(new Vec3(96, 71, -24));
        var handContext = RemoteCaptureContext.resolveForTransfer(env.player, env.player.main);
        check(handheld != null && valid(handheld) && handContext != null && handContext.level() == env.nether,
                "same-target handheld route continues using valid observation session");
        check(handContext.asHolderEntity().position().equals(new Vec3(12, 71, -3)), "valid handheld session follows current player displacement");
        env.open(2, NETHER, -1);
        var repeatedScene = (RemoteSceneStartS2C) PacketDistributor.scenes.getLast();
        check(repeatedScene.sourceOrigin().equals(new Vec3(80, 70, -40))
                        && repeatedScene.position().equals(new Vec3(10, 70, -5)),
                "retry after player movement preserves both initial scene anchors to avoid double movement");
    }

    private static void multipleStands() {
        var env = new Environment(); var first = env.stand(10, NETHER); var second = env.stand(11, END);
        var session = env.open(1, NETHER, first.getId());
        var secondContext = RemoteCaptureContext.resolveForTransfer(second, second.camera);
        check(session != null && secondContext != null && secondContext.level() == env.end,
                "two stand cameras of one UUID use their own target routes");
        check(secondContext.cameraStandId() == second.getId() && secondContext.asHolderEntity().position().equals(second.position()),
                "second camera cannot reuse first camera origin or stand identity");
        check(RemoteCameraSession.active(env.player.getUUID()) == session, "resolving another stand does not replace active viewfinder session");
        var firstContext = RemoteCaptureContext.resolveForTransfer(first, first.camera);
        check(firstContext != null && firstContext.level() == env.nether && firstContext.cameraStandId() == first.getId(),
                "first camera's valid session remains usable afterwards");
        env.player.main = camera(END);
        var hand = env.open(2, END, -1);
        var standFallback = RemoteCaptureContext.resolveForTransfer(first, first.camera);
        check(hand != null && standFallback != null && standFallback.level() == env.nether,
                "active handheld camera cannot overwrite another stand target");
    }

    private static void snapshotRoute() throws Exception {
        var env = new Environment(); var stand = env.stand(10, END);
        var session = env.open(1, END, stand.getId());
        var snapshot = camera(NETHER);
        check(valid(session), "current stand still owns a valid End viewfinder session");
        var route = RemoteCaptureContext.resolveForTransfer(stand, snapshot);
        check(route != null && route.level() == env.nether && route.coordinateScale() == 0.125,
                "same-ID argument camera snapshot resolves its own target rather than active session route");
        check(valid(session) && RemoteCameraSession.active(env.player.getUUID()) == session,
                "snapshot query does not invalidate unrelated current viewfinder session");
        env.player.main = camera(END); env.player.off = camera(NETHER);
        env.open(2, END, -1);
        check(RemoteCaptureContext.resolveForTransfer(env.player, env.player.off) == null,
                "offhand with different target cannot inherit main-hand observation context");
    }

    private static void ownerReplaced() throws Exception {
        var env = new Environment(); env.player.main = camera(END); var old = env.open(1, END, -1);
        var replacement = new ServerPlayer(2, env.player.getUUID(), env.server, env.source);
        replacement.main = env.player.main; env.server.players.players.put(replacement.getUUID(), replacement);
        check(!valid(old) && RemoteCameraSession.active(replacement.getUUID()) == null, "respawn player sharing UUID cannot use former entity's session");
        check(RemoteCaptureContext.resolveForTransfer(env.player, env.player.main) == null, "resolver upgrades owner reference and refuses stale handheld identity");
        RemoteCameraSession.handle(new CameraSessionRequestC2S(2, FILTER, END, -1), replacement);
        var current = RemoteCameraSession.active(replacement.getUUID());
        check(current != null && current != old && valid(current), "replacement owner can create a new valid session");
    }

    private static void invalidRoute() throws Exception {
        var env = new Environment(); var stand = env.stand(10, END); var session = env.open(1, END, stand.getId());
        DimensionFilters.routes.remove(new DimensionFilters.Key(END, SOURCE));
        check(!valid(session) && RemoteCaptureContext.resolveForTransfer(stand, stand.camera) == null, "missing route never keeps or invents prior target");
        env.route(END, 1);
        env.server.levels.put(env.end.dimension(), new ServerLevel(env.end.dimension(), 1));
        check(!valid(session), "replaced target world identity invalidates cached world reference");
        var fallback = RemoteCaptureContext.resolveForTransfer(stand, stand.camera);
        check(fallback != null && fallback.level() != env.end, "stand fallback uses replacement world registered by server");
        env.server.levels.remove(env.end.dimension());
        check(RemoteCaptureContext.resolveForTransfer(stand, stand.camera) == null, "missing target world cannot produce context");
        stand.camera.filter = ItemStack.EMPTY;
        check(RemoteCaptureContext.resolveForTransfer(stand, stand.camera) == null, "empty filter cannot inherit old session");
    }

    private static void expectedHandTarget() throws Exception {
        var env = new Environment(); env.player.main = camera(NETHER); env.player.off = camera(END);
        var offhand = env.open(1, END, -1);
        check(offhand != null && offhand.remoteLevel() == env.end && valid(offhand),
                "expected End target selects matching offhand instead of shared-ID Nether main hand");
        var mainHand = env.open(2, NETHER, -1);
        check(mainHand != null && mainHand.remoteLevel() == env.nether, "expected Nether target still selects main hand");
        env.player.main.active = false;
        var repeated = env.open(3, END, -1);
        check(repeated != null && repeated.remoteLevel() == env.end, "inactive main hand cannot block legitimate offhand route");
        RemoteCameraSession.handle(new CameraSessionRequestC2S(4, FILTER, ResourceLocation.parse("test:absent"), -1), env.player);
        check(RemoteCameraSession.active(env.player.getUUID()) == repeated, "unmatched requested target leaves existing legitimate session intact");
    }

    private static void delayedComponents() {
        var env = new Environment(); env.player.main = camera(NETHER);
        var old = env.open(1, NETHER, -1);
        int sceneCount = PacketDistributor.scenes.size();
        var request = new CameraSessionRequestC2S(2, FILTER, END, -1);
        RemoteCameraSession.handle(request, env.player);
        check(RemoteCameraSession.active(env.player.getUUID()) == old && PacketDistributor.scenes.size() == sceneCount,
                "early End request with stale Nether attachment is rejected without wrong scene acknowledgement");
        env.player.main = camera(END);
        RemoteCameraSession.handle(request, env.player);
        var current = RemoteCameraSession.active(env.player.getUUID());
        check(current != null && current != old && current.remoteLevel() == env.end,
                "same sequence retry succeeds after attachment synchronization");
        check(((RemoteSceneStartS2C) PacketDistributor.scenes.getLast()).dimension().equals(END), "successful retry acknowledges only expected target");
        RemoteCameraSession.handle(request, env.player);
        check(PacketDistributor.scenes.size() == sceneCount + 2, "repeat request recovers a lost scene acknowledgement without replacing session");
    }

    private static void requestCodec() {
        for (long seq : new long[]{1, -1, Long.MIN_VALUE}) for (ResourceLocation target : List.of(END, NETHER)) for (int stand : new int[]{-1, 10}) {
            var request = new CameraSessionRequestC2S(seq, FILTER, target, stand);
            var buffer = new FriendlyByteBuf(); CameraSessionRequestC2S.STREAM_CODEC.encode(buffer, request);
            check(CameraSessionRequestC2S.STREAM_CODEC.decode(buffer).equals(request), "request codec retains expected target along with shared ID, sequence and stand");
        }
    }

    private static void setupClient(ResourceLocation target) {
        var env = new Environment();
        Minecraft mc = Minecraft.getInstance();
        mc.player = new net.minecraft.world.entity.player.Player(3, new ClientLevel(key(SOURCE)));
        mc.connection = new ClientPacketListener();
        CameraClient.active = true; CameraClient.viewfinder = new Viewfinder(camera(target));
        ImmersiveCameraClient.onTick(new ClientTickEvent.Post());
    }

    private static void clientStart() throws Exception {
        setupClient(END);
        var request = PacketDistributor.requests.getLast();
        check(request.targetDimension().equals(END), "new handheld request explicitly binds expected End target");
        var wrongTarget = scene(request.sequence(), NETHER, SOURCE);
        ImmersiveCameraClient.start(wrongTarget);
        check(field(ImmersiveCameraClient.class, "scene") == null, "same sequence response with wrong target is rejected");
        ImmersiveCameraClient.start(scene(request.sequence(), END, NETHER));
        check(field(ImmersiveCameraClient.class, "scene") == null, "same sequence response with wrong source is rejected");
        ImmersiveCameraClient.start(scene(request.sequence() - 1, END, SOURCE));
        check(field(ImmersiveCameraClient.class, "scene") == null, "old sequence cannot contaminate expected target");
        var correct = scene(request.sequence(), END, SOURCE); ImmersiveCameraClient.start(correct);
        check(field(ImmersiveCameraClient.class, "scene") == correct, "matching sequence/source/target response is accepted");
        ImmersiveCameraClient.start(wrongTarget);
        check(field(ImmersiveCameraClient.class, "scene") == correct, "late wrong-target response cannot replace accepted scene");
        Minecraft.getInstance().connection = new ClientPacketListener();
        ImmersiveCameraClient.start(scene(request.sequence(), END, SOURCE));
        check(field(ImmersiveCameraClient.class, "scene") == correct, "connection change invalidates outstanding acknowledgement");
    }

    private static void clientRetry() throws Exception {
        setupClient(NETHER);
        var first = PacketDistributor.requests.getFirst();
        ImmersiveCameraClient.start(scene(first.sequence(), NETHER, SOURCE));
        CameraClient.viewfinder = new Viewfinder(camera(END));
        tick(1);
        var current = PacketDistributor.requests.getLast();
        check(current.sequence() != first.sequence() && current.targetDimension().equals(END), "Nether to End handheld switch creates a new target-bound sequence");
        ImmersiveCameraClient.start(scene(current.sequence(), NETHER, SOURCE));
        check(field(ImmersiveCameraClient.class, "scene") == null, "premature same-sequence Nether response leaves End request waiting");
        int before = PacketDistributor.requests.size(); tick(12);
        check(PacketDistributor.requests.size() == before + 1 && PacketDistributor.requests.getLast().equals(current),
                "unacknowledged End view retries the identical target-bound request");
        tick(22);
        check(PacketDistributor.requests.size() == before + 3, "waiting scene continues periodic retries without changing session identity");
        ImmersiveCameraClient.start(scene(current.sequence(), END, SOURCE));
        before = PacketDistributor.requests.size(); tick(30);
        check(PacketDistributor.requests.size() == before, "accepted scene stops retries");
        Minecraft.getInstance().player.world = new ClientLevel(key(NETHER));
        tick(1);
        check(((RemoteSceneStartS2C) field(ImmersiveCameraClient.class, "scene")) == null, "source world change closes the prior observed scene");
    }

    private static void captureCurrentWindow() throws Exception {
        var env = new Environment();
        var stand = env.stand(10, END);
        var preview = env.open(1, END, stand.getId());
        var loaderField = RemoteCameraSession.class.getDeclaredField("loader");
        loaderField.setAccessible(true);
        var originalLoader = (ChunkLoader) loaderField.get(preview);
        var smallWindow = new ChunkLoader(originalLoader.dimension(), originalLoader.x(), originalLoader.z(), 3);
        loaderField.set(preview, smallWindow);
        var remote = RemoteCaptureContext.resolveForTransfer(stand, stand.camera);
        int initialAdds = ChunkLoading.adds;
        var scene = RemoteCameraSession.openCapture(env.player, remote);
        var captureLoader = (ChunkLoader) ChunkLoading.loaders.getLast();
        check(ChunkLoading.adds == initialAdds + 1, "capture retains one target loader without adding a source group-photo loader");
        check(captureLoader.radius() == 3, "capture preserves the current preview radius instead of expanding to requested radius 8");
        check(captureLoader.equals(smallWindow) && captureLoader != smallWindow, "capture owns a separate identity while retaining the same preview window");
        check(scene.dimension().equals(END) && scene.position().equals(remote.asHolderEntity().position()), "capture keeps the same target dimension and camera origin");
        check(ChunkLoader.fullyLoadedQueries == 0, "capture creation never waits for the whole terrain window");

        var first = new ServerPlayer(2, UUID.randomUUID(), env.server, env.source);
        first.setPos(new Vec3(-1.25, 70, 33.5));
        var second = new ServerPlayer(3, UUID.randomUUID(), env.server, env.source);
        second.setPos(new Vec3(49, 70, -17));
        var firstKey = RemoteChunkTracking.key(env.source.dimension(), -1, 2);
        var secondKey = RemoteChunkTracking.key(env.source.dimension(), 3, -2);
        RemoteChunkTracking.missing.add(firstKey);
        RemoteChunkTracking.missing.add(secondKey);
        check(RemoteCameraSession.syncedCapturePlayers(scene.sequence(), List.of(env.player, first, second)).equals(List.of(env.player)),
                "local owner remains visible but unsent group-photo players do not delay or enter this photo");
        RemoteChunkTracking.missing.remove(firstKey);
        check(RemoteCameraSession.syncedCapturePlayers(scene.sequence(), List.of(first, second)).equals(List.of(first)),
                "already synchronized remote source player remains in original candidate order");
        RemoteChunkTracking.nativeChunks.add(secondKey);
        env.player.connection.chunkSender.pending.add(net.minecraft.world.level.ChunkPos.asLong(3, -2));
        check(RemoteCameraSession.syncedCapturePlayers(scene.sequence(), List.of(second)).isEmpty(), "pending native chunk does not count as already synchronized");
        env.player.connection.chunkSender.pending.clear();
        check(RemoteCameraSession.syncedCapturePlayers(scene.sequence(), List.of(first, second)).equals(List.of(first, second)),
                "vanilla source player remains eligible without an additional camera watch record");

        var candidates = new ArrayList<>(List.of(first, second));
        var frozen = remote.freezePlayersInFrame(candidates);
        candidates.clear();
        check(frozen.equals(List.of(first, second)) && remote.playersInFrame(stand.camera).equals(frozen),
                "the exact selected player list is frozen without depending on later candidate mutations");
        int initialRemoves = ChunkLoading.removes;
        RemoteCameraSession.close(env.player, scene.sequence());
        check(ChunkLoading.removes == initialRemoves + 1, "completion releases only the capture loader");
        check(RemoteCameraSession.active(env.player.getUUID()) == preview && loaderField.get(preview) == smallWindow,
                "closing capture leaves the preview session and its own loader unchanged");
        check(RemoteCameraSession.syncedCapturePlayers(scene.sequence(), List.of(first)).isEmpty(), "closed capture cannot reuse another transaction's player list");
    }

    private static void viewLimitDefault() throws Exception {
        var env = new Environment();
        env.server.players.viewDistance = 32;
        McHelper.loadDistance = 32;
        env.player.main = camera(END);
        var preview = env.open(1, END, -1);
        check(ShuttershadowConfig.maxViewDistance == 8, "camera configuration fixture matches the eight-chunk default");
        check(loader(preview, "loader").radius() == 8, "default target loading remains capped at eight chunks");
        check(lastScene().maxRenderDistance() == 8, "default scene advertises the eight-chunk camera limit");
        check(ChunkLoader.fullyLoadedQueries == 0, "default preview does not introduce a terrain readiness wait");
    }

    private static void viewLimitHandheld() throws Exception {
        var env = new Environment();
        env.server.players.viewDistance = 32;
        McHelper.loadDistance = 32;
        ShuttershadowConfig.maxViewDistance = 3;
        env.player.main = camera(END);
        var preview = env.open(1, END, -1);
        check(loader(preview, "loader").radius() == 3, "handheld target loading obeys the camera view limit");
        check(lastScene().maxRenderDistance() == 3, "handheld target scene advertises the camera view limit");
        check(loader(preview, "sourceLoader") == null && ChunkLoading.adds == 1,
                "handheld preview still adds only a target-dimension subscription");
    }

    private static void viewLimitStand() throws Exception {
        var env = new Environment();
        env.server.players.viewDistance = 32;
        McHelper.loadDistance = 32;
        ShuttershadowConfig.maxViewDistance = 3;
        var stand = env.stand(10, END);
        var preview = env.open(1, END, stand.getId());
        check(loader(preview, "loader").radius() == 3, "manual stand target loading obeys the minimum camera view limit");
        check(lastScene().maxRenderDistance() == 3, "manual stand scene advertises the minimum camera view limit");
        var source = loader(preview, "sourceLoader");
        check(source.dimension().equals(env.source.dimension()) && source.radius() == 8,
                "source group-photo synchronization keeps its configured player range when the remote limit is three chunks");
        check(ChunkLoading.adds == 2, "stand preview retains one independent source subscription and one target subscription");
    }

    private static void viewLimitClientServer() throws Exception {
        for (int[] distances : new int[][]{{3, 32, 32, 3, 3}, {12, 20, 2, 12, 2}, {24, 4, 24, 4, 4}, {32, 32, 32, 32, 32}, {32, 64, 64, 32, 32}}) {
            var env = new Environment();
            ShuttershadowConfig.maxViewDistance = distances[0];
            env.server.players.viewDistance = distances[1];
            McHelper.loadDistance = distances[2];
            env.player.main = camera(END);
            var preview = env.open(1, END, -1);
            check(lastScene().maxRenderDistance() == distances[3],
                    "advertised view distance honors both the camera limit and the native server limit");
            check(loader(preview, "loader").radius() == distances[4],
                    "actual subscription also honors a client's smaller load distance");
            RemoteCameraSession.close(env.player);
        }
    }

    private static void viewLimitLive() throws Exception {
        var env = new Environment();
        env.server.players.viewDistance = 16;
        McHelper.loadDistance = 8;
        ShuttershadowConfig.maxViewDistance = 6;
        var stand = env.stand(10, END);
        var preview = env.open(1, END, stand.getId());
        var source = loader(preview, "sourceLoader");
        var original = loader(preview, "loader");
        int adds = ChunkLoading.adds, removes = ChunkLoading.removes, scenes = PacketDistributor.scenes.size();
        ShuttershadowConfig.maxViewDistance = 3;
        RemoteCameraSession.onServerTick(new ServerTickEvent.Post());
        var smaller = loader(preview, "loader");
        check(smaller.radius() == 3 && smaller != original, "lowering the live limit replaces the target loader next tick");
        check(ChunkLoading.adds == adds + 1 && ChunkLoading.removes == removes + 1,
                "shrinking swaps a single target subscription without leaking its previous loader");
        check(lastScene().maxRenderDistance() == 3 && PacketDistributor.scenes.size() == scenes + 1,
                "lowering the live limit sends the new scene cap to the client");
        check(loader(preview, "sourceLoader") == source, "a remote limit change preserves source group-photo subscription identity");

        ShuttershadowConfig.maxViewDistance = 10;
        RemoteCameraSession.onServerTick(new ServerTickEvent.Post());
        check(loader(preview, "loader").radius() == 8 && lastScene().maxRenderDistance() == 10,
                "raising the limit expands preview loading only to the player's existing load distance");
        check(ChunkLoading.adds == adds + 2 && ChunkLoading.removes == removes + 2,
                "growing also swaps exactly one target subscription");
        scenes = PacketDistributor.scenes.size();
        McHelper.loadDistance = 2;
        RemoteCameraSession.onServerTick(new ServerTickEvent.Post());
        check(loader(preview, "loader").radius() == 2 && PacketDistributor.scenes.size() == scenes,
                "a smaller client preference changes loading without inventing a new server limit");
        check(loader(preview, "sourceLoader") == source && source.radius() == 8,
                "client and remote view changes do not truncate source group-photo synchronization");
        env.server.players.viewDistance = 3;
        RemoteCameraSession.onServerTick(new ServerTickEvent.Post());
        check(lastScene().maxRenderDistance() == 3 && loader(preview, "loader").radius() == 2,
                "live native server distance remains another hard bound for the advertised cap");
    }

    private static void viewLimitCapture() throws Exception {
        var env = new Environment();
        env.server.players.viewDistance = 32;
        McHelper.loadDistance = 32;
        ShuttershadowConfig.maxViewDistance = 8;
        var stand = env.stand(10, END);
        var preview = env.open(1, END, stand.getId());
        var original = loader(preview, "loader");
        var remote = RemoteCaptureContext.resolveForTransfer(stand, stand.camera);
        int adds = ChunkLoading.adds;
        ShuttershadowConfig.maxViewDistance = 3;
        var scene = RemoteCameraSession.openCapture(env.player, remote);
        var capture = (ChunkLoader) ChunkLoading.loaders.getLast();
        check(capture.radius() == 3 && scene.maxRenderDistance() == 3,
                "capture immediately honors a reduced cap even before the next preview refresh");
        check(loader(preview, "loader") == original && original.radius() == 8,
                "capture does not mutate the active preview's independent loader");
        check(ChunkLoading.adds == adds + 1, "capture retains only one bounded target window");
        RemoteCameraSession.close(env.player, scene.sequence());

        var smallWindow = new ChunkLoader(original.dimension(), original.x(), original.z(), 3);
        var loaderField = RemoteCameraSession.class.getDeclaredField("loader");
        loaderField.setAccessible(true);
        loaderField.set(preview, smallWindow);
        ShuttershadowConfig.maxViewDistance = 16;
        scene = RemoteCameraSession.openCapture(env.player, remote);
        check(((ChunkLoader) ChunkLoading.loaders.getLast()).radius() == 3,
                "raising the cap does not expand a captured current preview window");
        RemoteCameraSession.close(env.player, scene.sequence());
        RemoteCameraSession.close(env.player);
        ShuttershadowConfig.maxViewDistance = 4;
        scene = RemoteCameraSession.openCapture(env.player, remote);
        check(((ChunkLoader) ChunkLoading.loaders.getLast()).radius() == 4 && scene.maxRenderDistance() == 4,
                "capture without a matching preview clamps fallback loading to the same server camera limit");
        check(ChunkLoader.fullyLoadedQueries == 0, "bounded captures still do not wait for full terrain loading");
        RemoteCameraSession.close(env.player, scene.sequence());
    }

    private static ChunkLoader loader(RemoteCameraSession session, String name) throws Exception {
        var field = RemoteCameraSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ChunkLoader) field.get(session);
    }

    private static RemoteSceneStartS2C lastScene() {
        return (RemoteSceneStartS2C) PacketDistributor.scenes.getLast();
    }

    private static ItemStack camera(ResourceLocation target) {
        var filter = new ItemStack(SHARED_FILTER); filter.target = target;
        var camera = new ItemStack(CAMERA); camera.active = true; camera.filter = filter; return camera;
    }
    private static ResourceKey<Level> key(ResourceLocation id) { return ResourceKey.create(Registries.DIMENSION, id); }
    private static RemoteSceneStartS2C scene(long seq, ResourceLocation target, ResourceLocation source) { return new RemoteSceneStartS2C(seq, target, Vec3.ZERO, Vec3.ZERO, 1, 8, source, List.of()); }
    private static boolean valid(RemoteCameraSession session) throws Exception { var method = RemoteCameraSession.class.getDeclaredMethod("isValid"); method.setAccessible(true); return (Boolean) method.invoke(session); }
    private static Object field(Class<?> cls, String name) throws Exception { var field = cls.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
    private static void tick(int count) { for (int i = 0; i < count; i++) ImmersiveCameraClient.onTick(new ClientTickEvent.Post()); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}

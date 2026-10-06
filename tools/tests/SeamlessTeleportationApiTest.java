import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Executes the production public API against controlled server and transfer results. */
public final class SeamlessTeleportationApiTest {
    private static final String SOURCE = "com/xfw/shuttershadow/api/SeamlessTeleportation";
    private static final String FIXTURE = "SeamlessTeleportationApiFixture";
    private static final String TEST = "SeamlessTeleportationApiTest";
    private static java.lang.reflect.Method entityTransfer;
    private static java.lang.reflect.Method playerTransfer;
    private static int checks;
    private static int backendCalls;
    private static boolean locked;
    private static boolean explicit;
    private static String backendBehavior;

    public static void main(String[] args) throws Exception {
        ClassNode production = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(args[0], SOURCE + ".class"))).accept(production, 0);
        int backendTargets = 0;
        for (var method : production.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.name.equals("teleportEntityGeneral")) backendTargets++;
                    check(!call.name.equals("onTravelToDimension"), "API must preserve the existing forced transfer contract");
                }
            }
        }
        check(backendTargets == 1, "both public methods must use one existing transfer implementation");
        Class<?> fixture = fixture(production);
        entityTransfer = fixture.getMethod("teleportEntity", Entity.class, World.class, Position.class);
        playerTransfer = fixture.getMethod("teleportPlayer", Player.class, World.class, Position.class);
        invalidRequests();
        transferProtection();
        successfulTransfers();
        unsuccessfulResults();
        System.out.println("Seamless teleportation API production fixture passed " + checks + " checks.");
    }

    private static void invalidRequests() throws Exception {
        Scenario s = scenario();
        reject(null, s.target, s.destination, "null entity");
        reject(s.entity, null, s.destination, "null target");
        reject(s.entity, s.target, null, "null position");
        s.server.onThread = false;
        reject(s.entity, s.target, s.destination, "network thread");
        s.server.onThread = true;
        s.entity.alive = false;
        reject(s.entity, s.target, s.destination, "dead entity");
        s.entity.alive = true;
        s.entity.removed = true;
        reject(s.entity, s.target, s.destination, "removed entity");
        s.entity.removed = false;
        World foreign = new World(new Server(), "foreign");
        reject(s.entity, foreign, s.destination, "different server");
        World impostor = new World(s.server, "target");
        reject(s.entity, impostor, s.destination, "unregistered target instance");
        for (int axis = 0; axis < 3; axis++) {
            for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                double[] xyz = {1.0, 2.0, 3.0};
                xyz[axis] = invalid;
                reject(s.entity, s.target, new Position(xyz[0], xyz[1], xyz[2]), "nonfinite axis " + axis);
            }
        }
    }

    private static void transferProtection() throws Exception {
        Scenario s = scenario();
        Player player = new Player(s.source);
        locked = true;
        reject(player, s.target, s.destination, "protected player");
        reject(player, s.source, player.position(), "protected player already at target");
        check(playerTransfer.invoke(null, player, s.source, player.position()) == null && backendCalls == 0,
                "typed API must not report success for an identical position refused by camera protection");
        explicit = true;
        check(playerTransfer.invoke(null, player, s.target, s.destination) == player && backendCalls == 1,
                "explicit camera transfer must bypass its own protection and preserve the player reference");
        explicit = false;
        check(entityTransfer.invoke(null, s.entity, s.target, s.destination) instanceof Entity,
                "player protection must not block regular entity film transfers");
    }

    private static void successfulTransfers() throws Exception {
        Scenario s = scenario();
        Entity moved = (Entity) entityTransfer.invoke(null, s.entity, s.target, s.destination);
        check(moved != null && moved != s.entity && moved.level() == s.target,
                "cross-dimension entity transfer must return its replacement");
        check(moved.position().distanceToSqr(s.destination) == 0.0,
                "public destination must remain the requested feet position");
        check(s.entity.removed, "callers must not keep using the discarded source entity");
        check(backendCalls == 1, "API must invoke the existing backend once");
        backendCalls = 0;
        check(entityTransfer.invoke(null, moved, s.target, new Position(5, 6, 7)) == moved,
                "same-dimension ordinary entities must retain their identity");
        Player player = new Player(s.source);
        check(playerTransfer.invoke(null, player, s.target, s.destination) == player,
                "typed player transfer must preserve the server player object");
        backendBehavior = "rounding";
        check(playerTransfer.invoke(null, player, s.target, s.destination) == player,
                "success validation must tolerate harmless eye-position roundoff");
    }

    private static void unsuccessfulResults() throws Exception {
        for (String behavior : new String[] {"null", "wrong-level", "wrong-position", "removed", "dead", "refused"}) {
            Scenario s = scenario();
            backendBehavior = behavior;
            check(entityTransfer.invoke(null, s.entity, s.target, s.destination) == null,
                    "backend result " + behavior + " must not be exposed as a successful transfer");
            check(backendCalls == 1, "valid requests must reach the backend once for " + behavior);
        }
        Scenario s = scenario();
        Player player = new Player(s.source);
        backendBehavior = "null";
        check(playerTransfer.invoke(null, player, s.target, s.destination) == null,
                "typed API must propagate a backend failure");
    }

    private static void reject(Entity entity, World target, Position destination, String message) throws Exception {
        backendCalls = 0;
        check(entityTransfer.invoke(null, entity, target, destination) == null, "must reject " + message);
        check(backendCalls == 0, "rejected request must have no transfer side effect: " + message);
    }

    private static Scenario scenario() {
        backendCalls = 0;
        locked = explicit = false;
        backendBehavior = "success";
        Server server = new Server();
        World source = new World(server, "source");
        World target = new World(server, "target");
        server.levels.put(source.dimension(), source);
        server.levels.put(target.dimension(), target);
        return new Scenario(server, source, target, new Entity(source), new Position(12.5, 80.25, -32.5));
    }

    public static boolean shouldBlockPortalTeleport(Player player) { return locked; }
    public static boolean isExplicitTransferInProgress(Player player) { return explicit; }
    public static Entity teleportEntityGeneral(Entity entity, Position destination, World target) {
        backendCalls++;
        if (backendBehavior.equals("null")) return null;
        if (backendBehavior.equals("refused")) return entity;
        Entity moved = entity;
        if (!(entity instanceof Player) && entity.world != target) {
            moved = new Entity(target);
            entity.removed = true;
        }
        moved.world = backendBehavior.equals("wrong-level") ? entity.world : target;
        moved.position = backendBehavior.equals("wrong-position") ? new Position(0, 0, 0) : destination;
        if (backendBehavior.equals("rounding")) moved.position = new Position(destination.x, Math.nextUp(destination.y), destination.z);
        moved.removed = backendBehavior.equals("removed");
        moved.alive = !backendBehavior.equals("dead");
        return moved;
    }

    private static Class<?> fixture(ClassNode node) {
        Map<String, String> map = Map.of(
                SOURCE, FIXTURE,
                "net/minecraft/server/MinecraftServer", TEST + "$Server",
                "net/minecraft/server/level/ServerLevel", TEST + "$World",
                "net/minecraft/world/level/Level", TEST + "$World",
                "net/minecraft/server/level/ServerPlayer", TEST + "$Player",
                "net/minecraft/world/entity/Entity", TEST + "$Entity",
                "net/minecraft/resources/ResourceKey", "java/lang/String",
                "net/minecraft/world/phys/Vec3", TEST + "$Position",
                "com/xfw/shuttershadow/DimensionFilmCapture", TEST,
                "com/xfw/shuttershadow/core/teleportation/ServerTeleportationManager", TEST);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(map)));
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(SeamlessTeleportationApiTest.class.getClassLoader()) {
            Class<?> defineFixture() { return defineClass(FIXTURE, bytes, 0, bytes.length); }
        }.defineFixture();
    }
    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }
    private record Scenario(Server server, World source, World target, Entity entity, Position destination) {}
    public static class Server {
        boolean onThread = true;
        final Map<String, World> levels = new HashMap<>();
        public boolean isSameThread() { return onThread; }
        public World getLevel(String dimension) { return levels.get(dimension); }
    }
    public static class World {
        final Server server;
        final String dimension;
        World(Server server, String dimension) { this.server = server; this.dimension = dimension; }
        public Server getServer() { return server; }
        public String dimension() { return dimension; }
    }
    public static class Entity {
        World world;
        Position position = new Position(0, 64, 0);
        boolean removed;
        boolean alive = true;
        Entity(World world) { this.world = world; }
        public Server getServer() { return world.getServer(); }
        public World level() { return world; }
        public boolean isRemoved() { return removed; }
        public boolean isAlive() { return alive; }
        public Position position() { return position; }
    }
    public static class Player extends Entity { Player(World world) { super(world); } }
    public static class Position {
        public final double x, y, z;
        Position(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        public double distanceToSqr(Position other) {
            return (x-other.x)*(x-other.x)+(y-other.y)*(y-other.y)+(z-other.z)*(z-other.z);
        }
    }
}

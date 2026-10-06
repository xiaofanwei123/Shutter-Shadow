import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 用真实命令参数与生产字节码验证权限、坐标及批量传送结果，不启动服务器。 */
public final class SeamlessTeleportCommandTest {
    private static final String PRODUCTION = "com/xfw/shuttershadow/SeamlessTeleportCommand";
    private static final String FIXTURE = "SeamlessTeleportCommandFixture";
    private static final String TEST = "SeamlessTeleportCommandTest";
    private static Method teleport;
    private static int checks;

    public static void main(String[] args) throws Exception {
        ClassNode production = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(args[0], PRODUCTION + ".class"))).accept(production, 0);
        check(production.visibleAnnotations.stream().anyMatch(annotation ->
                annotation.desc.equals("Lnet/neoforged/fml/common/EventBusSubscriber;")), "automatic event subscriber");
        check(production.methods.stream().filter(method -> method.name.equals("register")).anyMatch(method ->
                method.visibleAnnotations.stream().anyMatch(annotation ->
                        annotation.desc.equals("Lnet/neoforged/bus/api/SubscribeEvent;"))), "command registration event handler");
        Class<?> fixture = fixture(production);
        teleport = fixture.getDeclaredMethod("teleport", Source.class, Collection.class, World.class, Vec3.class);
        teleport.setAccessible(true);
        commandTree(fixture);
        coordinates();
        transferResults();
        bounds();
        carriedVehicleReplacement();
        retainAuthoritativeTargets();
        System.out.println("Seamless teleport command production fixture passed " + checks + " checks.");
    }

    private static void commandTree(Class<?> fixture) throws Exception {
        CommandEvent event = new CommandEvent();
        fixture.getMethod("register", CommandEvent.class).invoke(null, event);
        var command = event.dispatcher.getRoot().getChild("tps");
        check(command != null, "registered /tps literal");
        check(!command.canUse(new Source(1)) && command.canUse(new Source(2)), "same operator permission as /tp");
        var targets = (ArgumentCommandNode<?, ?>) command.getChild("targets");
        var dimension = (ArgumentCommandNode<?, ?>) targets.getChild("dimension");
        var pos = (ArgumentCommandNode<?, ?>) dimension.getChild("pos");
        check(targets.getType() instanceof EntityArgument, "vanilla entity selection argument");
        check(dimension.getType() instanceof DimensionArgument, "vanilla dimension argument and completion");
        check(pos.getType() instanceof Vec3Argument && pos.getCommand() != null, "complete positional command");
        var all = ((EntityArgument) targets.getType()).parse(new StringReader("@e"));
        check(all.includesEntities() && all.getMaxResults() > 1, "selector accepts multiple mobs and players");
    }

    private static void coordinates() throws Exception {
        CommandSourceStack console = new CommandSourceStack(null, new Vec3(10, 70, -20), Vec2.ZERO,
                null, 2, "console", Component.literal("console"), null, null);
        var arg = Vec3Argument.vec3();
        Vec3 absolute = arg.parse(new StringReader("1 80 -2")).getPosition(console);
        check(absolute.equals(new Vec3(1.5, 80, -1.5)), "absolute integer X/Z centers like /tp");
        Vec3 relative = arg.parse(new StringReader("~2 ~-1 ~")).getPosition(console);
        check(relative.equals(new Vec3(12, 69, -20)), "relative coordinates resolve from a non-player source");
        Vec3 local = arg.parse(new StringReader("^1 ^2 ^3")).getPosition(console);
        check(local.distanceToSqr(new Vec3(11, 72, -17)) < 1.0E-8, "local coordinates use execution orientation");
    }

    private static void transferResults() throws Exception {
        for (int accepted = 0; accepted <= 3; accepted++) {
            Api.calls.clear();
            Source source = new Source(2);
            World world = new World();
            List<Target> targets = new ArrayList<>();
            for (int i = 0; i < 3; i++) targets.add(new Target(i < accepted));
            Vec3 destination = new Vec3(12.25, 400, -32.75);
            int completed = invoke(source, targets, world, destination);
            check(completed == accepted, "command result counts actual API successes: " + accepted);
            check(Api.calls.equals(targets), "every target passed exactly once to the public API");
            check(Api.lastWorld == world && Api.lastPosition == destination, "unscaled destination is passed to API");
            check(source.success.size() == (accepted == 0 ? 0 : 1), "success message only when at least one target moved");
            check(source.failure.size() == (accepted == 3 ? 0 : 1), "rejected targets receive failure feedback");
            if (accepted > 0) {
                var message = translation(source.success.getFirst());
                check(message.getKey().equals("commands.shuttershadow.tps.success"), "success translation");
                check(List.of(message.getArgs()).equals(List.of(accepted, "minecraft:the_nether", "12.250", "400.000", "-32.750")),
                        "success reports actual count, dimension and requested coordinates");
                check(source.broadcast, "successful admin transfer is broadcast to operators");
            }
            if (accepted < 3) {
                var message = translation(source.failure.getFirst());
                check(message.getKey().equals("commands.shuttershadow.tps.refused"), "refusal translation");
                check(List.of(message.getArgs()).equals(List.of(3 - accepted, accepted)), "refusal includes refused and successful counts");
            }
        }
    }

    private static void bounds() throws Exception {
        for (Vec3 outside : List.of(new Vec3(30_000_000, 0, 0), new Vec3(-30_000_000.01, 0, 0),
                new Vec3(0, 20_000_000, 0), new Vec3(0, -20_000_000.01, 0),
                new Vec3(0, 0, 30_000_000), new Vec3(0, 0, -30_000_000.01))) {
            Api.calls.clear();
            try {
                invoke(new Source(2), List.of(new Target(true)), new World(), outside);
                throw new AssertionError("out-of-bounds position accepted: " + outside);
            } catch (CommandSyntaxException expected) {
                check(translation((Component) expected.getRawMessage()).getKey().equals("commands.teleport.invalidPosition"),
                        "vanilla out-of-bounds error");
                check(Api.calls.isEmpty(), "invalid position must not transfer any target");
            }
        }
        for (Vec3 inside : List.of(new Vec3(-30_000_000, -20_000_000, -30_000_000),
                new Vec3(29_999_999.9, 19_999_999.9, 29_999_999.9))) {
            check(invoke(new Source(2), List.of(new Target(true)), new World(), inside) == 1,
                    "spawnable bounds, not build height or world border, control acceptance");
        }
    }

    private static void carriedVehicleReplacement() throws Exception {
        Api.calls.clear();
        World world = new World();
        Target player = new Target(true);
        Target oldVehicle = new Target(true);
        Target newVehicle = new Target(oldVehicle.uuid, true);
        player.carriedOriginal = oldVehicle;
        player.carriedReplacement = newVehicle;
        check(invoke(new Source(2), List.of(player, oldVehicle), world, new Vec3(1, 64, 2)) == 2,
                "player plus carried vehicle both succeed");
        check(Api.calls.equals(List.of(player, newVehicle)), "later target resolves the live vehicle instead of stale selector reference");
    }

    private static void retainAuthoritativeTargets() throws Exception {
        Api.calls.clear();
        World destination = new World();
        Target liveSource = new Target(true);
        Target duplicate = new Target(liveSource.uuid, true);
        destination.entities.put(duplicate.uuid, duplicate);
        check(invoke(new Source(2), List.of(liveSource), destination, new Vec3(1, 64, 2)) == 1,
                "registered source target remains eligible");
        check(Api.calls.equals(List.of(liveSource)), "destination UUID collision cannot substitute a still-registered source target");

        Api.calls.clear();
        Target stale = new Target(false);
        stale.world.entities.remove(stale.uuid);
        check(invoke(new Source(2), List.of(stale), destination, new Vec3(1, 64, 2)) == 0,
                "missing replacement is refused by the public API");
        check(Api.calls.equals(List.of(stale)), "missing replacement retains original API refusal handling");
    }

    private static int invoke(Source source, Collection<Target> targets, World world, Vec3 position) throws Exception {
        try {
            return (int) teleport.invoke(null, source, targets, world, position);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            throw exception;
        }
    }

    private static TranslatableContents translation(Component component) {
        return (TranslatableContents) component.getContents();
    }

    private static Class<?> fixture(ClassNode node) {
        Map<String, String> names = Map.of(
                PRODUCTION, FIXTURE,
                "net/minecraft/commands/CommandSourceStack", TEST + "$Source",
                "net/minecraft/commands/Commands", TEST + "$CommandBuilders",
                "net/neoforged/neoforge/event/RegisterCommandsEvent", TEST + "$CommandEvent",
                "net/minecraft/server/level/ServerLevel", TEST + "$World",
                "net/minecraft/world/level/Level", TEST + "$World",
                "net/minecraft/world/entity/Entity", TEST + "$Target",
                "com/xfw/shuttershadow/api/SeamlessTeleportation", TEST + "$Api");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(Opcodes.ASM9, names)));
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(SeamlessTeleportCommandTest.class.getClassLoader()) {
            Class<?> defineFixture() { return defineClass(FIXTURE, bytes, 0, bytes.length); }
        }.defineFixture();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static class CommandBuilders {
        public static LiteralArgumentBuilder<Source> literal(String name) { return LiteralArgumentBuilder.literal(name); }
        public static <T> RequiredArgumentBuilder<Source, T> argument(String name, ArgumentType<T> argument) {
            return RequiredArgumentBuilder.argument(name, argument);
        }
    }
    public static class CommandEvent {
        final CommandDispatcher<Source> dispatcher = new CommandDispatcher<>();
        public CommandDispatcher<Source> getDispatcher() { return dispatcher; }
    }
    public static class Source {
        final int permission;
        final List<Component> success = new ArrayList<>(), failure = new ArrayList<>();
        boolean broadcast;
        Source(int permission) { this.permission = permission; }
        public boolean hasPermission(int required) { return permission >= required; }
        public void sendSuccess(Supplier<Component> message, boolean broadcast) { success.add(message.get()); this.broadcast = broadcast; }
        public void sendFailure(Component message) { failure.add(message); }
    }
    public static class World {
        final Map<UUID, Target> entities = new HashMap<>();
        public Target getEntity(UUID uuid) { return entities.get(uuid); }
        public ResourceKey<Level> dimension() { return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:the_nether")); }
        public static boolean isInSpawnableBounds(BlockPos pos) { return Level.isInSpawnableBounds(pos); }
    }
    public static class Target {
        final UUID uuid;
        final boolean accepts;
        World world = new World();
        Target carriedOriginal;
        Target carriedReplacement;
        Target(boolean accepts) { this(UUID.randomUUID(), accepts); }
        Target(UUID uuid, boolean accepts) {
            this.uuid = uuid;
            this.accepts = accepts;
            world.entities.put(uuid, this);
        }
        public UUID getUUID() { return uuid; }
        public World level() { return world; }
    }
    public static class Api {
        static final List<Target> calls = new ArrayList<>();
        static World lastWorld;
        static Vec3 lastPosition;
        public static Target teleportEntity(Target target, World world, Vec3 position) {
            calls.add(target);
            lastWorld = world;
            lastPosition = position;
            if (!target.accepts) return null;
            if (target.carriedReplacement != null) {
                target.carriedOriginal.world.entities.remove(target.carriedOriginal.uuid);
                target.carriedReplacement.world = world;
                world.entities.put(target.carriedReplacement.uuid, target.carriedReplacement);
            }
            return new Target(target.uuid, true);
        }
    }
}

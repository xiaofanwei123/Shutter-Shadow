package com.xfw.shuttershadow;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.xfw.shuttershadow.api.SeamlessTeleportation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.Locale;

/** 通过公开 API 立即执行跨维度传送，沿用相机保护和原版命令坐标语义。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class SeamlessTeleportCommand {
    private static final SimpleCommandExceptionType INVALID_POSITION = new SimpleCommandExceptionType(
            Component.translatable("commands.teleport.invalidPosition"));

    private SeamlessTeleportCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tps")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(context -> teleport(
                                                context.getSource(),
                                                EntityArgument.getEntities(context, "targets"),
                                                DimensionArgument.getDimension(context, "dimension"),
                                                Vec3Argument.getVec3(context, "pos")))))));
    }

    /** 坐标先由命令源解析，再统一校验；任何目标移动前都拒绝越界坐标。 */
    private static int teleport(CommandSourceStack source, Collection<? extends Entity> targets,
                                ServerLevel dimension, Vec3 position) throws CommandSyntaxException {
        if (!Level.isInSpawnableBounds(BlockPos.containing(position))) {
            throw INVALID_POSITION.create();
        }

        int successful = 0;
        for (Entity target : targets) {
            // 先传送的玩家可能已携带并重建同批载具，不能再次操作选择器中的旧副本。
            Entity current = target;
            if (target.level() != dimension && target.level() instanceof ServerLevel sourceLevel
                    && sourceLevel.getEntity(target.getUUID()) != target) {
                Entity replacement = dimension.getEntity(target.getUUID());
                if (replacement != null) current = replacement;
            }
            // 普通实体跨维度后会被替换；非空返回值才表示 API 已确认成功。
            if (SeamlessTeleportation.teleportEntity(current, dimension, position) != null) successful++;
        }

        int completed = successful;
        if (completed > 0) {
            source.sendSuccess(() -> Component.translatable("commands.shuttershadow.tps.success",
                    completed, dimension.dimension().location().toString(),
                    formatCoordinate(position.x), formatCoordinate(position.y), formatCoordinate(position.z)), true);
        }
        int refused = targets.size() - completed;
        if (refused > 0) {
            source.sendFailure(Component.translatable("commands.shuttershadow.tps.refused", refused, completed));
        }
        return completed;
    }

    private static String formatCoordinate(double coordinate) {
        return String.format(Locale.ROOT, "%.3f", coordinate);
    }
}

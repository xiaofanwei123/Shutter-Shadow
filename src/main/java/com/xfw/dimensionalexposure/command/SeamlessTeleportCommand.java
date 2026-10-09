package com.xfw.dimensionalexposure.command;

import com.xfw.dimensionalexposure.DimensionalExposure;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.xfw.dimensionalexposure.api.SeamlessTeleportation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.Locale;

/** 注册OP权限2的/tps命令，原版实体选择器、维度参数和坐标参数接入无缝传送API。 */
@EventBusSubscriber(modid = DimensionalExposure.MODID)
public final class SeamlessTeleportCommand {
    private static final SimpleCommandExceptionType INVALID_POSITION = new SimpleCommandExceptionType(
            Component.translatable("commands.teleport.invalidPosition"));

    /** 禁止实例化此工具类。 */
    private SeamlessTeleportCommand() {}

    /** 注册tps→targets→dimension→pos参数树，解析后调用本类teleport。 */
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

    /** 检查世界坐标边界，逐个调用SeamlessTeleportation。 */
    private static int teleport(CommandSourceStack source, Collection<? extends Entity> targets,
                                ServerLevel dimension, Vec3 position) throws CommandSyntaxException {
        if (!SeamlessTeleportation.isValidTargetPosition(position)) {
            throw INVALID_POSITION.create();
        }

        int successful = 0;
        for (Entity target : targets) {
            // 普通实体跨维度后会被替换；非空返回值才表示 API 已确认成功。
            if (SeamlessTeleportation.teleportEntity(target, dimension, position) != null) successful++;
        }

        int completed = successful;
        if (completed > 0) {
            source.sendSuccess(() -> Component.translatable("commands.dimensional_exposure.tps.success",
                    completed, dimension.dimension().location().toString(),
                    formatCoordinate(position.x), formatCoordinate(position.y), formatCoordinate(position.z)), true);
        }
        int refused = targets.size() - completed;
        if (refused > 0) {
            source.sendFailure(Component.translatable("commands.dimensional_exposure.tps.refused", refused, completed));
        }
        return completed;
    }

    /** 使用Locale.ROOT把反馈坐标格式化为三位小数。 */
    private static String formatCoordinate(double coordinate) {
        return String.format(Locale.ROOT, "%.3f", coordinate);
    }
}

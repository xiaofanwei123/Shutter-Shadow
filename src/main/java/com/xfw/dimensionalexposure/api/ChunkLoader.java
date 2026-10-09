package com.xfw.dimensionalexposure.api;

import com.xfw.dimensionalexposure.util.McHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Objects;

/** 公开API的不可变区块窗口：维度、XZ区块中心、方形切比雪夫半径。 */
public record ChunkLoader(ResourceKey<Level> dimension, int x, int z, int radius) {
    /** 紧凑构造器要求维度非null，半径非负且坐标±半径不溢出。 */
    public ChunkLoader {
        Objects.requireNonNull(dimension, "dimension");
        if (radius < 0 || radius == Integer.MAX_VALUE
                || (long) x - radius < Integer.MIN_VALUE || (long) x + radius > Integer.MAX_VALUE
                || (long) z - radius < Integer.MIN_VALUE || (long) z + radius > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid chunk radius or overflowing region coordinates");
        }
    }

    /** 校验服务端线程，检查所有窗口区块已有实体ticking状态。 */
    public boolean isFullyLoaded(MinecraftServer server) {
        ChunkLoading.validateThread(server);
        ServerLevel serverWorld = server.getLevel(dimension);
        if (serverWorld == null) return false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!McHelper.isServerChunkFullyLoaded(serverWorld, new ChunkPos(x + dx, z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 遍历(2r+1)²窗口，回调携带维度、XZ及到中心最大轴距。 */
    public void foreachChunkPos(ChunkPosConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                consumer.consume(dimension, x + dx, z + dz, Math.max(Math.abs(dx), Math.abs(dz)));
            }
        }
    }

    /** 输出维度、中心XZ和半径调试文本。 */
    @Override
    public String toString() {
        return "(%s %d %d %d)".formatted(dimension.location(), x, z, radius);
    }

    /** 区块窗口遍历函数接口。 */
    @FunctionalInterface
    public interface ChunkPosConsumer {
        /** 接收一个区块坐标和切比雪夫中心距离。 */
        void consume(ResourceKey<Level> dimension, int x, int z, int distanceToSource);
    }
}

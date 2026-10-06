package com.xfw.shuttershadow.api;

import com.xfw.shuttershadow.util.McHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Objects;

/**
 * 包含边界的正方形区域，中心和半径的单位都是区块。半径零覆盖中心区块，
 * 半径一覆盖 3 × 3 个区块。构造对象不会加载区块。区域可按值比较或去重，
 * 但 {@link ChunkLoading} 中的注册必须按对象身份释放。
 */
public record ChunkLoader(ResourceKey<Level> dimension, int x, int z, int radius) {
    public ChunkLoader {
        Objects.requireNonNull(dimension, "dimension");
        if (radius < 0 || radius == Integer.MAX_VALUE
                || (long) x - radius < Integer.MIN_VALUE || (long) x + radius > Integer.MAX_VALUE
                || (long) z - radius < Integer.MIN_VALUE || (long) z + radius > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid chunk radius or overflowing region coordinates");
        }
    }

    /**
     * 在服务器线程检查可用于服务端 tick 的完整区块及实体加载状态，目标维度不存在时返回 false。
     * 不会主动加载区块，也不代表客户端收包、地形编译、光照或画面已经就绪。
     */
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

    /** 枚举区域，并传入各区块与中心的切比雪夫距离。 */
    public void foreachChunkPos(ChunkPosConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                consumer.consume(dimension, x + dx, z + dz, Math.max(Math.abs(dx), Math.abs(dz)));
            }
        }
    }

    @Override
    public String toString() {
        return "(%s %d %d %d)".formatted(dimension.location(), x, z, radius);
    }

    @FunctionalInterface
    public interface ChunkPosConsumer {
        void consume(ResourceKey<Level> dimension, int x, int z, int distanceToSource);
    }
}

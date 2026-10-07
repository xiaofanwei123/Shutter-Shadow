package com.xfw.shuttershadow.core.render;

import com.xfw.shuttershadow.Shuttershadow;


import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.core.CoreSettings;
import com.xfw.shuttershadow.access.IERenderSection;
import com.xfw.shuttershadow.access.IEWorldRenderer;
import com.xfw.shuttershadow.core.GcMonitor;
import com.xfw.shuttershadow.util.Helper;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.LongConsumer;

/** 为原版渲染器管理多个相机中心的区段网格。 */
@OnlyIn(Dist.CLIENT)
public class RemoteViewArea extends ViewArea {

    /** 保存单个水平区块的全部渲染区段与最近活跃时间。 */
    public static class Column {
        public long mark = 0;
        public RenderSection[] sections;

        /** 保存该区块的渲染区段数组。 */
        public Column(RenderSection[] sections) {
            this.sections = sections;
        }
    }

    /** 保存相机中心对应的渲染区段索引和最近活跃时间。 */
    public static class Preset {
        public final RenderSection[] data;
        public long lastActiveTime = 0;

        /** 保存索引数组。 */
        public Preset(
                RenderSection[] data
        ) {
            this.data = data;
        }
    }

    private final SectionRenderDispatcher factory;
    private final Long2ObjectOpenHashMap<Column> columnMap = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Preset> presets = new Long2ObjectOpenHashMap<>();

    public final int minSectionY;
    public final int endSectionY;

    private boolean isAlive = true;

    /** 区块卸载时，重置对应远景渲染区段。 */
    public static void onClientChunkUnload(LevelChunk chunk) {
        ResourceKey<Level> dimension = chunk.getLevel().dimension();
        LevelRenderer worldRenderer = ClientWorldLoader.WORLD_RENDERER_MAP.get(dimension);

        if (worldRenderer != null) {
            ViewArea viewArea = ((IEWorldRenderer) worldRenderer).ip_getBuiltChunkStorage();
            if (viewArea instanceof RemoteViewArea remoteViewArea) {
                remoteViewArea.onChunkUnload(chunk.getPos().x, chunk.getPos().z);
            }
        }
    }

    /** 客户端游戏刻结束后清理各维度过期的远景视区。 */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(CoreSettings.PostClientTickEvent.class, postClientTickEvent -> {
            if (ClientWorldLoader.getIsInitialized()) {
                for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
                    LevelRenderer worldRenderer =
                            ClientWorldLoader.getWorldRenderer(world.dimension());
                    ViewArea viewArea = ((IEWorldRenderer) worldRenderer).ip_getBuiltChunkStorage();
                    if (viewArea instanceof RemoteViewArea immPtlViewArea) {
                        immPtlViewArea.tick();
                    }
                }
            }
        });
    }

    /** 初始化视区，保存区段创建方式与世界高度边界。 */
    public RemoteViewArea(
            SectionRenderDispatcher sectionBuilder,
            Level world,
            int r,
            LevelRenderer worldRenderer
    ) {
        super(sectionBuilder, world, r, worldRenderer);
        factory = sectionBuilder;

        minSectionY = world.getMinSection();
        endSectionY = world.getMaxSection();
    }

    /** 创建视区索引，渲染区段按需生成。 */
    @Override
    protected void createSections(SectionRenderDispatcher sectionBuilder_1) {
        // 原版世界渲染器重载时会读取此数组长度。
        int num = this.sectionGridSizeX * this.sectionGridSizeY * this.sectionGridSizeZ;
        sections = new RenderSection[num];
    }

    /** 释放所有区段网格缓冲，并清空视区缓存。 */
    @Override
    public void releaseAllBuffers() {
        // 列才是 RenderSection 的所有者；远景 rawFetch 创建的列可能不属于任何预设。
        for (Column column : columnMap.values()) {
            for (RenderSection section : column.sections) {
                section.releaseBuffers();
            }
        }
        columnMap.clear();
        presets.clear();

        isAlive = false;
    }

    /** 取得或创建相机中心的视区预设，并切换当前区段索引。 */
    @Override
    public void repositionCamera(double playerX, double playerZ) {
        Minecraft.getInstance().getProfiler().push("built_section_storage");

        int cameraBlockX = Mth.floor(playerX);
        int cameraBlockZ = Mth.floor(playerZ);

        int cameraChunkX = cameraBlockX >> 4;
        int cameraChunkZ = cameraBlockZ >> 4;
        ChunkPos cameraChunkPos = new ChunkPos(
                cameraChunkX, cameraChunkZ
        );

        Preset preset = presets.computeIfAbsent(
                cameraChunkPos.toLong(),
                whatever -> {
                    return createPresetByChunkPos(cameraChunkX, cameraChunkZ);
                }
        );
        preset.lastActiveTime = System.nanoTime();

        this.sections = preset.data;

        Minecraft.getInstance().getProfiler().pop();
    }

    /** 根据真实区段坐标标记网格需要重建。 */
    @Override
    public void setDirty(int cx, int cy, int cz, boolean isImportant) {
        RenderSection builtChunk = provideBuiltChunkByChunkPos(cx, cy, cz);
        builtChunk.setDirty(isImportant);
    }

    /** 根据水平区块和合法高度取得渲染区段。 */
    public RenderSection provideBuiltChunkByChunkPos(int cx, int cy, int cz) {
        Column column = provideColumn(ChunkPos.asLong(cx, cz));
        int offsetChunkY = Mth.clamp(
                cy - level.getMinSection(), 0, level.getSectionsCount() - 1
        );
        return column.sections[offsetChunkY];
    }

    /** 为相机中心建立环形区段索引，保留已有区段的真实坐标。 */
    private Preset createPresetByChunkPos(int sectionX, int sectionZ) {
        RenderSection[] sections1 =
                new RenderSection[this.sectionGridSizeX * this.sectionGridSizeY * this.sectionGridSizeZ];

        for (int cx = 0; cx < this.sectionGridSizeX; ++cx) {
            int xBlockSize = this.sectionGridSizeX * 16;
            int xStart = (sectionX << 4) - xBlockSize / 2;
            int px = xStart + Math.floorMod(cx * 16 - xStart, xBlockSize);

            for (int cz = 0; cz < this.sectionGridSizeZ; ++cz) {
                int zBlockSize = this.sectionGridSizeZ * 16;
                int zStart = (sectionZ << 4) - zBlockSize / 2;
                int pz = zStart + Math.floorMod(cz * 16 - zStart, zBlockSize);

                Validate.isTrue(px % 16 == 0);
                Validate.isTrue(pz % 16 == 0);

                Column column = provideColumn(ChunkPos.asLong(px >> 4, pz >> 4));

                for (int offsetCy = 0; offsetCy < this.sectionGridSizeY; ++offsetCy) {
                    int index = this.getChunkIndex(cx, offsetCy, cz);
                    sections1[index] = column.sections[offsetCy];
                }
            }
        }

        return new Preset(sections1);
    }

    /** 遍历视区预设覆盖的水平区块，标记活跃区块列。 */
    private void foreachPresetCoveredChunkPoses(
            int centerChunkX, int centerChunkZ,
            LongConsumer func
    ) {
        int xBlockSize = this.sectionGridSizeX * 16;
        int xStart = (centerChunkX << 4) - xBlockSize / 2;
        int zBlockSize = this.sectionGridSizeZ * 16;
        int zStart = (centerChunkZ << 4) - zBlockSize / 2;
        for (int cx = 0; cx < this.sectionGridSizeX; ++cx) {
            int px = xStart + Math.floorMod(cx * 16 - xStart, xBlockSize);

            for (int cz = 0; cz < this.sectionGridSizeZ; ++cz) {
                int pz = zStart + Math.floorMod(cz * 16 - zStart, zBlockSize);

                Validate.isTrue(px % 16 == 0);
                Validate.isTrue(pz % 16 == 0);

                long sectionPos = ChunkPos.asLong(px >> 4, pz >> 4);

                func.accept(sectionPos);
            }
        }
    }

    // 原版对应方法为私有方法，此处保留等效实现。
    /** 将三维网格坐标转换为数组索引。 */
    private int getChunkIndex(int x, int y, int z) {
        return (z * this.sectionGridSizeY + y) * this.sectionGridSizeX + x;
    }

    /** 根据水平区块坐标取得或创建区块列。 */
    public Column provideColumn(long sectionPos) {
        return columnMap.computeIfAbsent(sectionPos, this::createColumn);
    }

    /** 创建覆盖世界完整高度的渲染区段列。 */
    private Column createColumn(long sectionPos) {
        RenderSection[] array = new RenderSection[sectionGridSizeY];

        int sectionX = ChunkPos.getX(sectionPos);
        int sectionZ = ChunkPos.getZ(sectionPos);

        int minY = level.getMinBuildHeight();

        for (int offsetCY = 0; offsetCY < sectionGridSizeY; offsetCY++) {
            RenderSection builtChunk = factory.new RenderSection(
                    0,
                    sectionX << 4, (offsetCY << 4) + minY, sectionZ << 4
            );

            array[offsetCY] = builtChunk;
        }

        return new Column(array);
    }

    /** 定期清理活跃视区中的过期缓存。 */
    private void tick() {
        if (!isAlive) {
            return;
        }

        ClientLevel worldClient = Minecraft.getInstance().level;
        if (worldClient != null) {
            if (GcMonitor.isMemoryNotEnough()) {
                if (worldClient.getGameTime() % 3 == 0) {
                    purge();
                }
            } else {
                if (worldClient.getGameTime() % 213 == 66) {
                    purge();
                }
            }
        }
    }

    /** 清理过期视区预设和不再被使用的区段列。 */
    private void purge() {
        Minecraft.getInstance().getProfiler().push("my_built_section_storage_purge");

        long dropTime = Helper.secondToNano(GcMonitor.isMemoryNotEnough() ? 3 : 20);

        long currentTime = System.nanoTime();

        presets.long2ObjectEntrySet().removeIf(entry -> {
            Preset preset = entry.getValue();

            long centerChunkPos = entry.getLongKey();

            boolean shouldDropPreset = shouldDropPreset(dropTime, currentTime, preset);

            if (!shouldDropPreset) {
                foreachPresetCoveredChunkPoses(
                        ChunkPos.getX(centerChunkPos),
                        ChunkPos.getZ(centerChunkPos),
                        columnChunkPos -> {
                            Column column = columnMap.get(columnChunkPos);
                            column.mark = currentTime;
                        }
                );
            }

            return shouldDropPreset;
        });

        long timeThreshold = Helper.secondToNano(5);

        ArrayDeque<RenderSection> toDelete = new ArrayDeque<>();

        columnMap.long2ObjectEntrySet().removeIf(entry -> {
            Column column = entry.getValue();

            boolean shouldRemove = currentTime - column.mark > timeThreshold;
            if (shouldRemove) {
                toDelete.addAll(Arrays.asList(column.sections));
            }

            return shouldRemove;
        });

        if (!toDelete.isEmpty()) {
            CoreSettings.PRE_GAME_RENDER_TASK_LIST.addTask(() -> {
                if (toDelete.isEmpty()) {
                    return true;
                }

                int num = 0;
                while (!toDelete.isEmpty() && num < 100) {
                    RenderSection builtChunk = toDelete.poll();
                    builtChunk.releaseBuffers();
                    num++;
                }

                return false;
            });
        }

        Minecraft.getInstance().getProfiler().pop();
    }

    /** 保留当前视区预设，判断其他预设是否已经过期。 */
    private boolean shouldDropPreset(long dropTime, long currentTime, Preset preset) {
        if (preset.data == this.sections) {
            return false;
        }
        return currentTime - preset.lastActiveTime > dropTime;
    }

    /** 重置区块列的全部区段，取消任务并清空网格状态。 */
    public void onChunkUnload(int sectionX, int sectionZ) {
        long sectionPos = ChunkPos.asLong(sectionX, sectionZ);
        Column column = columnMap.get(sectionPos);
        if (column != null) {
            for (RenderSection builtChunk : column.sections) {
                ((IERenderSection) builtChunk).portal_fullyReset();
            }
        }
    }

    // 此数据可能被其他线程访问。
    /** 将方块位置转换为当前视区的环形索引。 */
    @Nullable
    @Override
    protected RenderSection getRenderSectionAt(BlockPos pos) {
        int i = Mth.floorDiv(pos.getX(), 16);
        int j = Mth.floorDiv(pos.getY() - level.getMinBuildHeight(), 16);
        int k = Mth.floorDiv(pos.getZ(), 16);
        if (j >= 0 && j < this.sectionGridSizeY) {
            i = Mth.positiveModulo(i, this.sectionGridSizeX);
            k = Mth.positiveModulo(k, this.sectionGridSizeZ);
            int sectionIndex = this.getChunkIndex(i, j, k);
            RenderSection result = this.sections[sectionIndex];

            if (result == null) {
                Shuttershadow.LOGGER.error("Null RenderChunk {}", pos);
                return null;
            }

            ((IERenderSection) result).portal_setIndex(sectionIndex);
            return result;
        } else {
            return null;
        }
    }

    /** 按真实坐标取得渲染区段，并更新区块列的活跃时间。 */
    @Nullable
    public RenderSection rawFetch(int cx, int cy, int cz, long timeMark) {
        if (cy < minSectionY || cy >= endSectionY) {
            return null;
        }

        long l = ChunkPos.asLong(cx, cz);
        Column column = provideColumn(l);

        column.mark = timeMark;

        int yOffset = cy - minSectionY;

        return column.sections[yOffset];
    }

}

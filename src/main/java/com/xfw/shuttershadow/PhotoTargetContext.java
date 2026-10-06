package com.xfw.shuttershadow;

import io.github.mortuusars.exposure.neoforge.api.event.ModifyFrameExtraDataEvent;
import io.github.mortuusars.exposure.util.ExtraData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Exposure照片ExtraData补写shuttershadow来源维度/位置/群系，区分远场照片和先传送再自拍。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class PhotoTargetContext {
    public static final ExtraData.Type<ResourceLocation> SOURCE_DIMENSION = ExtraData.Type.resourceLocation("shuttershadow_source_dimension");
    public static final ExtraData.Type<Vec3> SOURCE_POSITION = ExtraData.Type.vec3("shuttershadow_source_position");
    public static final ExtraData.Type<ResourceLocation> SOURCE_BIOME = ExtraData.Type.resourceLocation("shuttershadow_source_biome");

    /** 禁止实例化此工具类。 */
    private PhotoTargetContext() {
    }

    /** 远场CameraHolder写真实源实体信息。 */
    @SubscribeEvent
    public static void apply(ModifyFrameExtraDataEvent event) {
        ExtraData data = event.getData();
        if (event.getCameraHolder() instanceof RemoteCaptureContext remote) {
            Entity source = remote.source().asHolderEntity();
            write(data, source.level().dimension().location(), source.position(),
                    source.level().getBiome(source.blockPosition()).unwrapKey()
                            .map(key -> key.location()).orElse(null));
            return;
        }

        // 维度胶卷自拍在切换世界后使用真实玩家，
        // 此时不再使用远维度拍摄上下文。
        DimensionFilmCapture.SourceSnapshot source =
                DimensionFilmCapture.activeSource(event.getCameraHolder());
        if (source != null) write(data, source.dimension(), source.position(), source.biome());
    }

    /** 写来源维度和位置，群系非null时追加群系键。 */
    private static void write(ExtraData data, ResourceLocation dimension,
                              Vec3 position, ResourceLocation biome) {
        data.put(SOURCE_DIMENSION, dimension);
        data.put(SOURCE_POSITION, position);
        if (biome != null) data.put(SOURCE_BIOME, biome);
    }
}

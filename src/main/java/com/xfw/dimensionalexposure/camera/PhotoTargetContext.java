package com.xfw.dimensionalexposure.camera;

import io.github.mortuusars.exposure.util.ExtraData;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** 保留照片的真实来源标签，不向目标维度照片插入投影玩家信息。 */
public final class PhotoTargetContext {
    public static final ExtraData.Type<ResourceLocation> SOURCE_DIMENSION = ExtraData.Type.resourceLocation("dimensional_exposure_source_dimension");
    public static final ExtraData.Type<Vec3> SOURCE_POSITION = ExtraData.Type.vec3("dimensional_exposure_source_position");
    public static final ExtraData.Type<ResourceLocation> SOURCE_BIOME = ExtraData.Type.resourceLocation("dimensional_exposure_source_biome");

    private PhotoTargetContext() {}

    public static Frame withSourceMetadata(Frame frame, @Nullable CaptureSnapshot source) {
        if (source == null || source.getObservationDimension() == null && !source.isSelfie()) return frame;
        Frame.Mutable mutable = new Frame.Mutable(frame);
        ExtraData data = mutable.getTag();
        data.put(SOURCE_DIMENSION, source.getSourceLevel().dimension().location());
        data.put(SOURCE_POSITION, source.getSourcePosition());
        source.getSourceLevel().getBiome(BlockPos.containing(source.getSourcePosition()))
                .unwrapKey().ifPresent(key -> data.put(SOURCE_BIOME, key.location()));
        return mutable.toImmutable();
    }
}

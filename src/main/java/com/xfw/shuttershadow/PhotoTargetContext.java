package com.xfw.shuttershadow;

import io.github.mortuusars.exposure.neoforge.api.event.ModifyFrameExtraDataEvent;
import io.github.mortuusars.exposure.util.ExtraData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Records where the photographer stood when Exposure captured a remote scene.
 *
 * <p>RemoteCaptureContext makes Exposure's existing world probes use the
 * server's target dimension, so Exposure already writes the target position,
 * dimension, light, and biome to its own frame fields.</p>
 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class PhotoTargetContext {
    public static final ExtraData.Type<ResourceLocation> SOURCE_DIMENSION = ExtraData.Type.resourceLocation("shuttershadow_source_dimension");
    public static final ExtraData.Type<Vec3> SOURCE_POSITION = ExtraData.Type.vec3("shuttershadow_source_position");
    public static final ExtraData.Type<ResourceLocation> SOURCE_BIOME = ExtraData.Type.resourceLocation("shuttershadow_source_biome");

    private PhotoTargetContext() {
    }

    /** Exposure writes the target scene; only the source context is extra. */
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

        // A dimension-film selfie uses the real player after IP changes worlds,
        // so the RemoteCaptureContext no longer exists at this point.
        DimensionFilmCapture.SourceSnapshot source =
                DimensionFilmCapture.activeSource(event.getCameraHolder());
        if (source != null) write(data, source.dimension(), source.position(), source.biome());
    }

    private static void write(ExtraData data, ResourceLocation dimension,
                              Vec3 position, ResourceLocation biome) {
        data.put(SOURCE_DIMENSION, dimension);
        data.put(SOURCE_POSITION, position);
        if (biome != null) data.put(SOURCE_BIOME, biome);
    }
}

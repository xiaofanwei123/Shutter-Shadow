package com.xfw.shuttershadow.camera;

import com.xfw.shuttershadow.Shuttershadow;

import com.xfw.shuttershadow.api.event.CameraFrameEvent;
import io.github.mortuusars.exposure.util.ExtraData;
import io.github.mortuusars.exposure.util.PointOfView;
import io.github.mortuusars.exposure.world.camera.capture.CaptureParameters;
import io.github.mortuusars.exposure.world.camera.frame.EntityInFrame;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** 补充照片来源信息和手动支架画面中的投影玩家元数据。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class PhotoTargetContext {
    public static final ExtraData.Type<ResourceLocation> SOURCE_DIMENSION = ExtraData.Type.resourceLocation("shuttershadow_source_dimension");
    public static final ExtraData.Type<Vec3> SOURCE_POSITION = ExtraData.Type.vec3("shuttershadow_source_position");
    public static final ExtraData.Type<ResourceLocation> SOURCE_BIOME = ExtraData.Type.resourceLocation("shuttershadow_source_biome");

    /** 禁止实例化此工具类。 */
    private PhotoTargetContext() {
    }

    /** 将选中的源维度玩家补入支架远景照片，保留原排序和十个实体上限。 */
    public static Frame addProjectedPlayers(CameraHolder holder, ItemStack camera,
                                            CaptureParameters parameters, Frame frame) {
        if (parameters.projection().isEmpty() && holder instanceof RemoteCaptureContext remote
                && remote.cameraStandId() >= 0) {
            List<ServerPlayer> candidates = CameraCaptureEvents.projectedPhotoPlayers(camera);
            if (!candidates.isEmpty()) {
                Frame.Mutable mutable = new Frame.Mutable(frame);
                List<EntityInFrame> entitiesInFrame = new ArrayList<>(mutable.getEntitiesInFrame());
                CameraItem cameraItem = (CameraItem) camera.getItem();
                PointOfView view = cameraItem.getPointOfView(remote, camera);
                double fov = cameraItem.getViewfinderFov(remote.level(), camera);
                for (ServerPlayer candidate : candidates) {
                    addProjectedPlayer(remote, candidate, view, fov, entitiesInFrame);
                }
                if (entitiesInFrame.size() > 10) entitiesInFrame.subList(10, entitiesInFrame.size()).clear();
                mutable.setEntitiesInFrame(entitiesInFrame);
                frame = mutable.toImmutable();
            }
        }
        return frame;
    }

    /** 按原视锥检查投影玩家，去重后按距离插入照片名单。 */
    private static void addProjectedPlayer(RemoteCaptureContext remote, ServerPlayer player,
                                           PointOfView view, double fov,
                                           List<EntityInFrame> entitiesInFrame) {
        Entity projectedEntity = remote.projectedPlayer(player);
        if (!ExposureVisibility.isVisible(view, projectedEntity, fov)) return;
        ResourceLocation playerId = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);
        Vec3 targetPosition = projectedEntity.position();
        int distance = Math.max(0, Mth.floor(remote.asHolderEntity().distanceTo(projectedEntity)));
        EntityInFrame projected = new EntityInFrame(playerId, player.getName().getString(),
                BlockPos.containing(targetPosition), distance, ExtraData.EMPTY);
        if (entitiesInFrame.stream().anyMatch(entity -> playerId.equals(entity.id())
                && projected.pos().equals(entity.pos()) && projected.name().equals(entity.name()))) return;
        int insertAt = entitiesInFrame.size();
        for (int i = 0; i < entitiesInFrame.size(); i++) {
            if (entitiesInFrame.get(i).distance() > distance) {
                insertAt = i;
                break;
            }
        }
        if (insertAt < 10) entitiesInFrame.add(insertAt, projected);
    }

    /** 远场CameraHolder写真实源实体信息。 */
    @SubscribeEvent
    public static void apply(CameraFrameEvent event) {
        var source = event.getContext();
        if (source.getObservationDimension() == null && !source.isSelfie()) return;
        write(event.getExtraData(), source.getSourceLevel().dimension().location(), source.getSourcePosition(),
                source.getSourceLevel().getBiome(net.minecraft.core.BlockPos.containing(source.getSourcePosition()))
                        .unwrapKey().map(key -> key.location()).orElse(null));
    }

    /** 写来源维度和位置，群系非null时追加群系键。 */
    private static void write(ExtraData data, ResourceLocation dimension,
                              Vec3 position, ResourceLocation biome) {
        data.put(SOURCE_DIMENSION, dimension);
        data.put(SOURCE_POSITION, position);
        if (biome != null) data.put(SOURCE_BIOME, biome);
    }
}

package com.xfw.dimensionalexposure.camera;

import io.github.mortuusars.exposure.world.entity.CameraHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 未加入世界的观察实体，服务端选择和客户端镜头共用尺寸、眼高及目标世界碰撞。 */
public final class CameraObservation extends Marker implements CameraHolder {
    private final EntityDimensions observerDimensions;

    public CameraObservation(Level level, Entity source, Vec3 feet, float partialTick) {
        super(EntityType.MARKER, level);
        observerDimensions = source.getDimensions(source.getPose()).withEyeHeight(source.getEyeHeight());
        setPose(source.getPose());
        refreshDimensions();
        setPos(feet);
        setXRot(Mth.lerp(partialTick, source.xRotO, source.getXRot()));
        setYRot(Mth.rotLerp(partialTick, source.yRotO, source.getYRot()));
    }

    @Override
    public Entity asHolderEntity() { return this; }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return observerDimensions != null ? observerDimensions : super.getDimensions(pose);
    }

    @Override
    public boolean isUnderWater() {
        Vec3 eye = getEyePosition();
        BlockPos pos = BlockPos.containing(eye);
        if (!level().hasChunkAt(pos)) return false;
        var fluid = level().getFluidState(pos);
        return fluid.is(FluidTags.WATER) && eye.y < pos.getY() + fluid.getHeight(level(), pos);
    }
}

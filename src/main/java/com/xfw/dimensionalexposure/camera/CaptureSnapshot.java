package com.xfw.dimensionalexposure.camera;

import io.github.mortuusars.exposure.world.entity.CameraHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** 一次拍摄的内部身份和来源快照，异步换维后仍引用原拍摄。 */
public final class CaptureSnapshot {
    public enum Trigger { HANDHELD, MANUAL_STAND, REDSTONE }
    private final Trigger trigger;
    private final CameraHolder holder;
    private final ServerPlayer executor;
    private final ServerLevel sourceLevel;
    private final Vec3 sourcePosition;
    private final boolean selfie;
    private final ResourceLocation observationDimension;

    /** 固定来源世界、位置和真实执行者，不复制整台相机。 */
    CaptureSnapshot(Trigger trigger, CameraHolder holder, ServerPlayer executor, boolean selfie,
                    @Nullable ResourceLocation observationDimension) {
        this.trigger = trigger;
        this.holder = holder;
        this.executor = executor;
        sourceLevel = (ServerLevel) holder.asHolderEntity().level();
        sourcePosition = holder.asHolderEntity().position();
        this.selfie = selfie;
        this.observationDimension = observationDimension;
    }

    public Trigger getTrigger() { return trigger; }
    public CameraHolder getHolder() { return holder; }
    public ServerPlayer getExecutor() { return executor; }
    public ServerLevel getSourceLevel() { return sourceLevel; }
    public Vec3 getSourcePosition() { return sourcePosition; }
    public boolean isSelfie() { return selfie; }
    public @Nullable ResourceLocation getObservationDimension() { return observationDimension; }
}

package com.xfw.shuttershadow.api;

import io.github.mortuusars.exposure.world.entity.CameraHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 一次相机拍摄的固定身份和来源快照，不随异步换维改变。 */
public final class CameraCaptureContext {
    /** 区分手持、手动支架和红石支架入口。 */
    public enum Trigger { HANDHELD, MANUAL_STAND, REDSTONE }
    private final UUID shotId = UUID.randomUUID();
    private final Trigger trigger;
    private final CameraHolder holder;
    private final ServerPlayer executor;
    private final ItemStack camera;
    private final ServerLevel sourceLevel;
    private final Vec3 sourcePosition;
    private final boolean selfie;
    private final ResourceLocation observationDimension;

    /** 保存本次拍摄真实来源和相机快照。 */
    public CameraCaptureContext(Trigger trigger, CameraHolder holder, ServerPlayer executor,
                                ItemStack camera, boolean selfie,
                                @Nullable ResourceLocation observationDimension) {
        this.trigger = trigger;
        this.holder = holder;
        this.executor = executor;
        this.camera = camera.copy();
        sourceLevel = (ServerLevel) holder.asHolderEntity().level();
        sourcePosition = holder.asHolderEntity().position();
        this.selfie = selfie;
        this.observationDimension = observationDimension;
    }
    /** 返回唯一拍摄编号。 */
    public UUID getShotId() { return shotId; }
    /** 返回触发方式，红石不代表某个玩家按下按钮。 */
    public Trigger getTrigger() { return trigger; }
    /** 返回真实持有者，支架拍摄时不是截图执行玩家。 */
    public CameraHolder getHolder() { return holder; }
    /** 返回负责截图和上传的在线玩家。 */
    public ServerPlayer getExecutor() { return executor; }
    /** 返回相机快照副本。 */
    public ItemStack getCamera() { return camera.copy(); }
    /** 返回拍摄开始时的世界。 */
    public ServerLevel getSourceLevel() { return sourceLevel; }
    /** 返回拍摄开始时的持有者位置。 */
    public Vec3 getSourcePosition() { return sourcePosition; }
    /** 返回开始时是否自拍。 */
    public boolean isSelfie() { return selfie; }
    /** 返回开始时观察维度，没有远场时为空。 */
    public @Nullable ResourceLocation getObservationDimension() { return observationDimension; }
}

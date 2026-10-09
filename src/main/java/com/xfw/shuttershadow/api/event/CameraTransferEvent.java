package com.xfw.shuttershadow.api.event;

import com.xfw.shuttershadow.api.CameraCaptureContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/** 相机选中的单个主体传送事件，不包含普通命令或直接无缝API调用。 */
public abstract class CameraTransferEvent extends Event {
    private final @Nullable CameraCaptureContext context;
    private final ItemStack camera;
    private final Entity entity;
    private final UUID entityId;
    private final ServerLevel sourceLevel;
    private final Vec3 sourcePosition;
    protected ServerLevel targetLevel;
    protected Vec3 targetPosition;
    protected float yaw;
    protected float pitch;

    /** 保存选中主体的来源快照，目标参数在前置事件中可调整。 */
    protected CameraTransferEvent(@Nullable CameraCaptureContext context, ItemStack camera,
                                  Entity entity,
                                  ServerLevel targetLevel, Vec3 targetPosition, float yaw, float pitch) {
        this.context = context;
        this.camera = camera.copy();
        this.entity = entity;
        entityId = entity.getUUID();
        sourceLevel = (ServerLevel) entity.level();
        sourcePosition = entity.position();
        this.targetLevel = targetLevel;
        this.targetPosition = targetPosition;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** 保留前置事件记录的来源，不从已换维实体重新读取。 */
    protected CameraTransferEvent(Before before) {
        context = before.getContext();
        camera = before.getCamera();
        entity = before.getEntity();
        entityId = before.getEntityId();
        sourceLevel = before.getSourceLevel();
        sourcePosition = before.getSourcePosition();
        targetLevel = before.targetLevel;
        targetPosition = before.targetPosition;
        yaw = before.yaw;
        pitch = before.pitch;
    }

    /** 返回所属拍摄；未建立拍摄事务时为null。 */
    public @Nullable CameraCaptureContext getContext() { return context; }

    /** 返回相机快照副本，修改不会写回实际相机。 */
    public ItemStack getCamera() { return camera.copy(); }

    /** 返回这次显式请求传送的原主体，普通实体跨维后可能已移除。 */
    public Entity getEntity() { return entity; }

    /** 返回不随普通实体重建变化的主体UUID。 */
    public UUID getEntityId() { return entityId; }

    /** 返回传送开始前的真实世界。 */
    public ServerLevel getSourceLevel() { return sourceLevel; }

    /** 返回传送开始前的脚底位置。 */
    public Vec3 getSourcePosition() { return sourcePosition; }

    /** 返回本次请求的目标世界。 */
    public ServerLevel getTargetLevel() { return targetLevel; }

    /** 返回本次请求的目标脚底位置。 */
    public Vec3 getTargetPosition() { return targetPosition; }

    /** 返回本次请求的水平朝向。 */
    public float getYaw() { return yaw; }

    /** 返回本次请求的俯仰角。 */
    public float getPitch() { return pitch; }

    /** 取消只影响本次主体传送，不取消照片或同一照片的其他主体。 */
    public static final class Before extends CameraTransferEvent implements ICancellableEvent {
        /** 建立单个主体的可取消传送请求。 */
        public Before(@Nullable CameraCaptureContext context, ItemStack camera, Entity entity,
                      ServerLevel targetLevel, Vec3 targetPosition,
                      float yaw, float pitch) {
            super(context, camera, entity, targetLevel, targetPosition, yaw, pitch);
        }

        /** 修改目标世界，执行前会校验服务器和世界身份。 */
        public void setTargetLevel(ServerLevel targetLevel) {
            this.targetLevel = Objects.requireNonNull(targetLevel);
        }

        /** 修改目标脚底位置，执行前会拒绝非有限或超出原版范围的坐标。 */
        public void setTargetPosition(Vec3 targetPosition) {
            this.targetPosition = Objects.requireNonNull(targetPosition);
        }

        /** 修改水平朝向，执行前会拒绝非有限数值。 */
        public void setYaw(float yaw) { this.yaw = yaw; }

        /** 修改俯仰角，执行前会拒绝非有限数值。 */
        public void setPitch(float pitch) { this.pitch = pitch; }
    }

    /** 区分成功、监听者取消、参数拒绝和执行异常。 */
    public enum Result {
        SUCCESS,
        CANCELED,
        REJECTED,
        FAILED
    }

    /** 传送结束通知不可取消，失败不承诺回滚已经发生的世界变更。 */
    public static final class After extends CameraTransferEvent {
        private final Result result;
        private final @Nullable Entity movedEntity;
        private final @Nullable RuntimeException failure;

        /** 保存前置请求与最终执行结果。 */
        public After(Before before, Result result, @Nullable Entity movedEntity,
                     @Nullable RuntimeException failure) {
            super(before);
            this.result = result;
            this.movedEntity = movedEntity;
            this.failure = failure;
        }

        /** 返回本次主体传送的最终结果。 */
        public Result getResult() { return result; }

        /** 返回无缝API已经确认成功的目标实体，未确认成功时为null。 */
        public @Nullable Entity getMovedEntity() { return movedEntity; }

        /** 返回执行异常，取消或参数拒绝时为null。 */
        public @Nullable RuntimeException getFailure() { return failure; }

        /** 判断本次主体是否已经成功传送。 */
        public boolean isSuccessful() { return result == Result.SUCCESS; }
    }
}

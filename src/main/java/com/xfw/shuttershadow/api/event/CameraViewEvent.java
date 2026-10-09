package com.xfw.shuttershadow.api.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** 公开真实相机取景的开关和每刻更新；临时后台截图不属于取景会话。 */
public abstract class CameraViewEvent extends Event {
    private final Context context;

    /** 保存本次取景状态快照。 */
    protected CameraViewEvent(Context context) { this.context = Objects.requireNonNull(context); }

    /** 返回快照；来源玩家为真实玩家，相机物品返回独立副本。 */
    public Context getContext() { return context; }

    /** 区分客户端界面事件和服务端远场会话事件。 */
    public enum Side { CLIENT, SERVER }

    /** 普通向前取景或手持自拍；支架只支持普通模式。 */
    public enum Mode { NORMAL, SELFIE }

    /** 说明取景结束原因；结束通知不能阻止资源清理。 */
    public enum CloseReason {
        NORMAL, CAMERA_CHANGED, DIMENSION_CHANGED, DEATH, DISCONNECTED,
        INVALIDATED, SERVER_STOPPING, EVENT_REQUESTED
    }

    /** 每端独立的会话标识、真实来源、远场脚底位置及模式快照。 */
    public record Context(long sessionId, Side side, Player player, ItemStack camera,
                          int cameraStandId, ResourceLocation sourceDimension, Vec3 sourcePosition,
                          @Nullable ResourceLocation targetDimension, @Nullable Vec3 targetPosition,
                          Mode mode) {
        /** 保存不可变字段并隔离相机物品快照。 */
        public Context {
            Objects.requireNonNull(side);
            Objects.requireNonNull(player);
            camera = Objects.requireNonNull(camera).copy();
            Objects.requireNonNull(sourceDimension);
            Objects.requireNonNull(sourcePosition);
            Objects.requireNonNull(mode);
        }

        /** 返回物品快照的副本，避免监听者改变其他监听者的输入。 */
        @Override public ItemStack camera() { return camera.copy(); }

        /** 仅手持相机支持自拍。 */
        public boolean supportsSelfie() { return cameraStandId < 0; }
    }

    /** 打开时和更新时的受控修改；客户端改模式，服务端改远场。 */
    public abstract static class Mutable extends CameraViewEvent {
        private Mode mode;
        private ResourceLocation targetDimension;
        private Vec3 targetPosition;
        private Context snapshot;

        /** 从原始快照建立可修改的取景请求。 */
        protected Mutable(Context context) {
            super(context);
            mode = context.mode();
            targetDimension = context.targetDimension();
            targetPosition = context.targetPosition();
            snapshot = context;
        }

        /** 返回目前各监听者共同修改后的快照。 */
        @Override public Context getContext() {
            if (snapshot == null) {
                Context original = super.getContext();
                snapshot = new Context(original.sessionId(), original.side(), original.player(), original.camera,
                        original.cameraStandId(), original.sourceDimension(), original.sourcePosition(),
                        targetDimension, targetPosition, mode);
            }
            return snapshot;
        }

        /** 仅客户端可改初始或当前模式；支架拒绝自拍。 */
        public boolean trySetMode(Mode mode) {
            Objects.requireNonNull(mode);
            if (super.getContext().side() != Side.CLIENT
                    || mode == Mode.SELFIE && !super.getContext().supportsSelfie()) return false;
            if (this.mode != mode) {
                this.mode = mode;
                snapshot = null;
            }
            return true;
        }

        /** 仅服务端可修改实际远场；位置为空时沿用目标维度原生坐标换算。 */
        public boolean trySetScene(ResourceLocation dimension, @Nullable Vec3 position) {
            Objects.requireNonNull(dimension);
            if (super.getContext().side() != Side.SERVER || position != null && !finite(position)) return false;
            if (!dimension.equals(targetDimension) || !Objects.equals(position, targetPosition)) {
                targetDimension = dimension;
                targetPosition = position;
                snapshot = null;
            }
            return true;
        }

        /** 防止无效坐标进入区块订阅和渲染。 */
        private static boolean finite(Vec3 position) {
            return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                    && Math.abs(position.x) <= 30_000_000 && Math.abs(position.z) <= 30_000_000;
        }
    }

    /** 初始化一次真实取景会话；重复同步同一相机不重新发布。 */
    public static final class Open extends Mutable {
        /** 保存打开时的模式和场景。 */
        public Open(Context context) { super(context); }
    }

    /** 每个有效游戏刻发布一次；可请求改模式、改远场或正常结束取景。 */
    public static final class Tick extends Mutable {
        private boolean closeRequested;

        /** 保存本刻有效取景状态。 */
        public Tick(Context context) { super(context); }

        /** 请求按正常流程关闭本端取景会话。 */
        public void requestClose() { closeRequested = true; }

        /** 返回监听者是否请求结束取景。 */
        public boolean isCloseRequested() { return closeRequested; }
    }

    /** 真实会话结束后的通知；相机清理始终执行。 */
    public static final class Close extends CameraViewEvent {
        private final CloseReason reason;

        /** 保存结束前快照和原因。 */
        public Close(Context context, CloseReason reason) {
            super(context);
            this.reason = Objects.requireNonNull(reason);
        }

        /** 返回会话结束原因。 */
        public CloseReason getReason() { return reason; }
    }
}

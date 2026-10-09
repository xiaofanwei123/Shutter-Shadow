package com.xfw.shuttershadow.api.event;

import com.xfw.shuttershadow.api.CameraCaptureContext;
import com.xfw.shuttershadow.api.CameraCapturePlan;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

import java.util.List;
import java.util.UUID;

/** 服务端一次逻辑拍摄的受理和终结事件，延迟恢复不会重复发布。 */
public abstract class CameraCaptureEvent extends Event {
    private final CameraCaptureContext context;
    /** 保存本次拍摄身份。 */
    protected CameraCaptureEvent(CameraCaptureContext context) { this.context = context; }
    /** 返回固定拍摄身份和来源快照。 */
    public CameraCaptureContext getContext() { return context; }

    /** 在换维和成片副作用之前取消拍摄或调整计划。 */
    public static final class Before extends CameraCaptureEvent implements ICancellableEvent {
        private final CameraCapturePlan plan;
        /** 保存尚可修改的计划。 */
        public Before(CameraCaptureContext context, CameraCapturePlan plan) { super(context); this.plan = plan; }
        /** 返回计划，事件结束后被冻结。 */
        public CameraCapturePlan getPlan() { return plan; }
    }
    /** 拍摄结果区分已接收图片、免成片、取消和失败。 */
    public enum Result { IMAGE_RECEIVED, NO_IMAGE, CANCELED, FAILED }
    /** 报告拍摄终态，不撤销此前已经发生的传送。 */
    public static final class Completed extends CameraCaptureEvent {
        private final Result result;
        private final boolean imageReceived;
        private final boolean filmWritten;
        private final List<UUID> transferred;
        private final String failureReason;
        /** 保存一次完成结果和成功传送对象。 */
        public Completed(CameraCaptureContext context, Result result, boolean imageReceived, boolean filmWritten,
                         List<UUID> transferred, String failureReason) {
            super(context); this.result = result; this.imageReceived = imageReceived; this.filmWritten = filmWritten;
            this.transferred = List.copyOf(transferred); this.failureReason = failureReason;
        }
        /** 返回终态，接收图片不代表世界数据已落盘。 */
        public Result getResult() { return result; }
        /** 返回是否已接收本次图片。 */
        public boolean hasImage() { return imageReceived; }
        /** 返回是否真正写入胶卷。 */
        public boolean wasFilmWritten() { return filmWritten; }
        /** 返回实际成功传送的主体UUID。 */
        public List<UUID> getTransferredEntities() { return transferred; }
        /** 返回取消或失败原因，成功时为空字符串。 */
        public String getFailureReason() { return failureReason; }
    }
}

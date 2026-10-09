package com.xfw.shuttershadow.api.event;

import com.xfw.shuttershadow.api.CameraCaptureContext;
import io.github.mortuusars.exposure.util.ExtraData;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import net.neoforged.bus.api.Event;

import java.util.Objects;

/** 照片数据全部生成后、写入胶卷前修改整帧，免成片也发布。 */
public final class CameraFrameEvent extends Event {
    private final CameraCaptureContext context;
    private Frame.Mutable frame;
    private final String exposureId;
    /** 保存照片数据和不可更换的图片编号。 */
    public CameraFrameEvent(CameraCaptureContext context, Frame frame) {
        this.context = context; this.frame = new Frame.Mutable(frame); exposureId = frame.identifier().id();
    }
    /** 返回拍摄上下文。 */
    public CameraCaptureContext getContext() { return context; }
    /** 返回当前照片快照。 */
    public Frame getFrame() { return frame.toImmutable(); }
    /** 替换照片数据，但禁止修改绑定上传的图片编号。 */
    public void replaceFrame(Frame replacement) {
        Objects.requireNonNull(replacement);
        if (!exposureId.equals(replacement.identifier().id())) throw new IllegalArgumentException("不能修改照片编号");
        frame = new Frame.Mutable(replacement);
    }
    /** 返回可修改的额外数据。 */
    public ExtraData getExtraData() { return frame.getTag(); }
}

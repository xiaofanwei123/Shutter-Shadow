package com.xfw.shuttershadow.api.event;

import io.github.mortuusars.exposure.client.image.Image;
import io.github.mortuusars.exposure.world.camera.CameraId;
import io.github.mortuusars.exposure.world.camera.capture.CaptureParameters;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 客户端原始照片图片事件，在主线程发布，不代表服务端已经收图。 */
public abstract class CameraImageEvent extends Event {
    private final CaptureParameters parameters;
    private final ResourceLocation sourceDimension;
    private final int cameraHolderId;

    /** 保存独立的参数快照和创建截图任务时的相机来源身份。 */
    protected CameraImageEvent(CaptureParameters parameters, ResourceLocation sourceDimension,
                               int cameraHolderId) {
        this.parameters = copyParameters(parameters);
        this.sourceDimension = Objects.requireNonNull(sourceDimension);
        this.cameraHolderId = cameraHolderId;
    }

    /** 返回参数副本，修改额外数据不会影响当前拍摄或其他监听器。 */
    public CaptureParameters getCaptureParameters() {
        return copyParameters(parameters);
    }

    /** 返回服务端分配的曝光标识。 */
    public String getExposureId() {
        return parameters.exposureId();
    }

    /** 返回拍摄相机的稳定标识，原生参数没有标识时为空。 */
    public Optional<CameraId> getCameraId() {
        return parameters.cameraId();
    }

    /** 返回源相机所在维度，远景照片不会误用目标维度。 */
    public ResourceLocation getSourceDimension() {
        return sourceDimension;
    }

    /** 返回源相机持有者的实体编号，支架照片对应源支架。 */
    public int getCameraHolderId() {
        return cameraHolderId;
    }

    /** 复制可变额外数据，其他参数保持 Exposure 原值。 */
    private static CaptureParameters copyParameters(CaptureParameters parameters) {
        return new CaptureParameters(parameters.exposureId(), parameters.cameraId(),
                parameters.cameraHolderId(), parameters.fov(), parameters.cropFactor(),
                parameters.filter(), parameters.projection(), parameters.singleChannel(),
                parameters.filmProperties(), parameters.extraData().copy());
    }

    /** 原始图片已生成且尚未进入颜色处理，监听器可读取或替换但不要自行关闭图片。 */
    public static final class Ready extends CameraImageEvent {
        private final Set<Image> retired = Collections.newSetFromMap(new IdentityHashMap<>());
        private Image image;

        /** 接管原始图片供本次同步事件处理，最终图片继续交给 Exposure。 */
        public Ready(CaptureParameters parameters, ResourceLocation sourceDimension,
                     int cameraHolderId, Image image) {
            super(parameters, sourceDimension, cameraHolderId);
            this.image = Objects.requireNonNull(image);
        }

        /** 返回借用的当前图片，只在本次事件处理期间读取或构造独立替换图片。 */
        public Image getImage() {
            return image;
        }

        /** 接管独立替换图片并关闭旧图片；传入同一图片不重复关闭，已丢弃图片不能重新使用。 */
        public void replaceImage(Image replacement) {
            Objects.requireNonNull(replacement);
            if (replacement == image) return;
            if (retired.contains(replacement)) {
                throw new IllegalArgumentException("Cannot reuse an image already replaced and closed");
            }
            Image previous = image;
            image = replacement;
            retired.add(previous);
            previous.close();
        }
    }
}

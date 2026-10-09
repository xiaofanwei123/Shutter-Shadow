package com.xfw.shuttershadow.client;

import com.xfw.shuttershadow.api.event.CameraImageEvent;
import io.github.mortuusars.exposure.client.image.Image;
import io.github.mortuusars.exposure.util.cycles.task.NestedTask;
import io.github.mortuusars.exposure.util.cycles.task.Task;
import io.github.mortuusars.exposure.world.camera.capture.CaptureParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.NeoForge;

import java.util.concurrent.CompletableFuture;

/** 在原始图片和 Exposure 颜色处理之间插入主线程事件，不阻塞截图线程。 */
public final class CameraImageEvents {
    /** 禁止实例化此工具类。 */
    private CameraImageEvents() {
    }

    /** 保留原任务的计时和错误链，仅成功得到图片时切回主线程发布事件。 */
    public static Task<Image> beforeEffects(Task<Image> images, CaptureParameters parameters,
                                           ResourceLocation sourceDimension, int cameraHolderId) {
        return new NestedTask<>(images) {
            /** 异步接续主线程事件，完成后由原链处理颜色、调色板和上传。 */
            @Override
            public CompletableFuture<Image> execute() {
                return getTask().execute().thenCompose(image -> Minecraft.getInstance().submit(
                        () -> publish(parameters, sourceDimension, cameraHolderId, image)));
            }
        };
    }

    /** 交给监听器读取或替换图片，监听异常时释放仍归事件所有的图片。 */
    private static Image publish(CaptureParameters parameters, ResourceLocation sourceDimension,
                                 int cameraHolderId, Image image) {
        CameraImageEvent.Ready event = new CameraImageEvent.Ready(parameters, sourceDimension,
                cameraHolderId, image);
        try {
            NeoForge.EVENT_BUS.post(event);
            return event.getImage();
        } catch (RuntimeException | Error exception) {
            try {
                event.getImage().close();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != exception) exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }
}

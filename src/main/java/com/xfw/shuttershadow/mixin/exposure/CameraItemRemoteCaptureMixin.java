package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.xfw.shuttershadow.camera.DimensionFilmCapture;
import com.xfw.shuttershadow.camera.CameraCaptureEvents;
import com.xfw.shuttershadow.api.CameraCaptureContext;
import com.xfw.shuttershadow.camera.MobDimensionFilmCapture;
import com.xfw.shuttershadow.network.RemoteStandPreparation;
import com.xfw.shuttershadow.camera.RemoteCaptureContext;
import com.xfw.shuttershadow.camera.PhotoTargetContext;
import com.xfw.shuttershadow.util.CaptureEntitySearchRange;
import io.github.mortuusars.exposure.util.ExtraData;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.camera.capture.CaptureParameters;
import io.github.mortuusars.exposure.world.camera.capture.Projection;
import io.github.mortuusars.exposure.util.PointOfView;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import io.github.mortuusars.exposure.network.packet.Packet;
import io.github.mortuusars.exposure.network.packet.clientbound.CaptureStartS2CP;
import io.github.mortuusars.exposure.world.level.LevelUtil;
import io.github.mortuusars.exposure.world.level.storage.ExposureRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Optional;

/** 将 Exposure 相机接入维度拍摄、胶卷传送和附魔流程。 */
@Mixin(value = CameraItem.class, remap = false)
public abstract class CameraItemRemoteCaptureMixin implements DimensionFilmCapture.TakePhotoInvoker {

    /** Invoker 暴露原 takePhoto 给内部准备事务恢复调用。 */
    @Invoker("takePhoto")
    @Override
    public abstract void shuttershadow$invokeTakePhoto(CameraHolder holder, ServerPlayer player,
                                                       ItemStack camera);

    /** 将维度自拍和支架拍摄交给对应事务准备流程。 */
    @WrapMethod(method = "takePhoto")
    private void shuttershadow$dimensionFilm(CameraHolder holder, ServerPlayer player,
                                             ItemStack camera, Operation<Void> original) {
        if (!CameraCaptureEvents.begin((CameraItem) (Object) this, holder, player, camera)) {
            RemoteStandPreparation.finishOrdinaryCapture(holder);
            return;
        }
        CameraCaptureContext context = CameraCaptureEvents.context(camera);
        try {
            if ((getProjection(camera).isEmpty()
                    && RemoteStandPreparation.beginIfNeeded((CameraItem) (Object) this, holder, player, camera))
                    || DimensionFilmCapture.beginIfNeeded((CameraItem) (Object) this, holder, player, camera)) return;
            original.call(holder, player, camera);
            if (!RemoteStandPreparation.shouldDeferTeleport(holder)) {
                DimensionFilmCapture.teleportStandPlayersAfterPhoto(holder, camera);
                RemoteStandPreparation.finishOrdinaryCapture(holder);
            }
            CameraCaptureEvents.nativeReturned(camera);
        } catch (RuntimeException exception) {
            CameraCaptureEvents.failed(context, "执行拍摄时发生异常");
            throw exception;
        }
    }

    /** 包裹 addNewFrame 的 addFrameToFilm：曝光失效直接跳过写卷。 */
    @WrapOperation(method = "addNewFrame", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/world/item/camera/CameraItem;addFrameToFilm(Lnet/minecraft/world/item/ItemStack;Lio/github/mortuusars/exposure/world/camera/frame/Frame;)V"))
    private void shuttershadow$commitAfterScreenshot(CameraItem item, ItemStack camera, Frame frame,
                                                      Operation<Void> original) {
        // 仍执行后面的 onFrameAdded，保留统计、进度和实体被拍行为。
        if (CameraCaptureEvents.discardsImage(camera)) return;
        if (!RemoteStandPreparation.deferFrameCommit(camera, frame)) {
            original.call(item, camera, frame);
            CameraCaptureEvents.filmWritten(camera);
        }
    }

    /** 实体元数据由本模组事件维护，停止派发 Exposure 原生修改事件。 */
    @WrapOperation(method = "lambda$createFrame$18", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/PlatformHelper;postModifyEntityInFrameExtraDataEvent(Lio/github/mortuusars/exposure/world/entity/CameraHolder;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lio/github/mortuusars/exposure/util/ExtraData;)V"))
    private static void shuttershadow$replaceEntityMetadataEvent(CameraHolder holder, ItemStack camera,
            LivingEntity entity, ExtraData data, Operation<Void> original) {
    }

    /** 保留原生照片元数据生成，仅停止派发 Exposure 原生修改事件。 */
    @WrapOperation(method = "addFrameExtraData", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/PlatformHelper;postModifyFrameExtraDataEvent(Lio/github/mortuusars/exposure/world/entity/CameraHolder;Lnet/minecraft/world/item/ItemStack;Lio/github/mortuusars/exposure/world/camera/capture/CaptureParameters;Ljava/util/List;Ljava/util/List;Lio/github/mortuusars/exposure/util/ExtraData;)V"))
    private void shuttershadow$replaceFrameMetadataEvent(CameraHolder holder, ItemStack camera,
            CaptureParameters parameters, List<BlockPos> positions, List<LivingEntity> entities,
            ExtraData data, Operation<Void> original) {
    }

    /** 保留帧历史、统计、进度和实体行为，仅停止 Exposure 原生帧完成事件。 */
    @WrapOperation(method = "onFrameAdded", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/PlatformHelper;postFrameAddedEvent(Lio/github/mortuusars/exposure/world/entity/CameraHolder;Lnet/minecraft/world/item/ItemStack;Lio/github/mortuusars/exposure/world/camera/frame/Frame;Ljava/util/List;Ljava/util/List;)V"))
    private void shuttershadow$replaceFrameAddedEvent(CameraHolder holder, ItemStack camera,
            Frame frame, List<BlockPos> positions, List<LivingEntity> entities, Operation<Void> original) {
    }

    /** 按服务端半径查询远景生物，并从红石照片中移除玩家。 */
    @WrapOperation(method = "addNewFrame", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/world/camera/frame/EntitiesInFrame;get(Lio/github/mortuusars/exposure/world/entity/CameraHolder;Lio/github/mortuusars/exposure/util/PointOfView;D)Ljava/util/List;"))
    private List<LivingEntity> shuttershadow$mobCaptureRange(CameraHolder holder, PointOfView view,
            double fov, Operation<List<LivingEntity>> original, @Local(argsOnly = true) ItemStack camera) {
        Integer radius = holder instanceof RemoteCaptureContext && CameraCaptureEvents.transfersMobs(camera)
                ? CameraCaptureEvents.mobRadius(camera) : null;
        List<LivingEntity> entities = CaptureEntitySearchRange.withRadius(radius,
                () -> original.call(holder, view, fov));
        // 红石源照片不记录玩家；传送名单由独立的源视锥资格查询决定。
        return CameraCaptureEvents.photoSubjects(holder, camera, view, fov, radius, entities);
    }

    /** 将支架快门关闭动作延迟到截图完成。 */
    @WrapMethod(method = "onShutterClosed")
    private void shuttershadow$finishShutterAfterScreenshot(CameraHolder holder, ServerLevel level,
                                                            ItemStack camera, Operation<Void> original) {
        if (!RemoteStandPreparation.deferShutterClosed(holder, () -> original.call(holder, level, camera))) {
            original.call(holder, level, camera);
        }
    }

    /** 包裹 takePhoto 的 ExposureRepository.expect：曝光失效不期待上传。 */
    @WrapOperation(method = "takePhoto", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/world/level/storage/ExposureRepository;expect(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/String;)V"))
    private void shuttershadow$mobFilmUploadCallback(ExposureRepository repository, ServerPlayer player,
                                                     String exposureId, Operation<Void> original,
                                                     @Local(argsOnly = true) ItemStack camera) {
        if (CameraCaptureEvents.discardsImage(camera)) return;
        if (!MobDimensionFilmCapture.expectUpload(repository, player, exposureId)) {
            original.call(repository, player, exposureId);
        }
    }

    /** 曝光失效时跳过截图请求，直接完成无需上传的生物事务。 */
    @WrapOperation(method = "takePhoto", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/network/Packets;sendToClient(Lio/github/mortuusars/exposure/network/packet/Packet;Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void shuttershadow$discardExposureImage(Packet packet, ServerPlayer player,
                                                   Operation<Void> original,
                                                   @Local(argsOnly = true) ItemStack camera) {
        if (CameraCaptureEvents.discardsImage(camera)) {
            MobDimensionFilmCapture.completeWithoutUpload(player,
                    ((CaptureStartS2CP) packet).captureParameters().exposureId());
        } else {
            original.call(packet, player);
        }
    }

    /** 将截图参数交给支架事务补充场景和曝光编号。 */
    @ModifyArg(method = "takePhoto", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/network/packet/clientbound/CaptureStartS2CP;<init>(Lnet/minecraft/resources/ResourceLocation;Lio/github/mortuusars/exposure/world/camera/capture/CaptureParameters;)V"), index = 1)
    private CaptureParameters shuttershadow$standCaptureScene(CaptureParameters parameters,
            @Local(argsOnly = true) CameraHolder holder,
            @Local(argsOnly = true) ServerPlayer player,
            @Local(argsOnly = true) ItemStack camera) {
        return RemoteStandPreparation.captureParameters(holder, player, camera, parameters);
    }

    /** 将源维度出镜玩家补入手动支架远景照片的实体元数据。 */
    @Inject(method = "createFrame", at = @At("RETURN"), cancellable = true)
    private void shuttershadow$addProjectedStandOperator(
            CameraHolder holder, ServerLevel level, ItemStack camera,
            CaptureParameters parameters, List<BlockPos> positions,
            List<LivingEntity> entities,
            CallbackInfoReturnable<Frame> callback) {
        Frame frame = PhotoTargetContext.addProjectedPlayers(holder, camera, parameters, callback.getReturnValue());
        callback.setReturnValue(CameraCaptureEvents.frame(camera, frame));
    }

    /** Shadow 声明原 getProjection，用于让现有投影照片避开维度相机替换。 */
    @Shadow
    protected abstract Optional<Projection> getProjection(ItemStack camera);

    /** 远景拍摄采用目标维度光照，普通拍摄采用源世界光照。 */
    @Redirect(method = "takePhoto", at = @At(value = "INVOKE",
            target = "Lio/github/mortuusars/exposure/world/level/LevelUtil;getLightLevelAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)I"))
    private int shuttershadow$targetLight(Level level, BlockPos position,
                                          CameraHolder holder, ServerPlayer player, ItemStack camera) {
        // 若相机带有投影（如望远镜），则不做远程捕获，保持原逻辑；
        // 否则尝试解析远程捕获上下文（需要玩家、会话、滤镜均匹配）。
        RemoteCaptureContext remote = getProjection(camera).isEmpty()
                ? CameraCaptureEvents.photoContext(holder, camera) : null;
        // 远程上下文存在时，使用远程维度与远程观察者位置的光照；
        // 否则回退到原始世界与位置。
        return remote == null ? LevelUtil.getLightLevelAt(level, position)
                : LevelUtil.getLightLevelAt(remote.level(), remote.asHolderEntity().blockPosition());
    }

    /** 远景拍摄时替换世界和持有者，让元数据来自目标维度。 */
    @WrapMethod(method = "addNewFrame")
    private void shuttershadow$remoteCapture(ServerLevel level, CameraHolder holder, ItemStack camera,
                                             CaptureParameters parameters, Operation<Void> original) {
        // 若参数中已包含投影（非空），说明不是普通拍照，不做远程替换。
        // 否则尝试解析远程上下文（依赖会话、滤镜匹配等）。
        RemoteCaptureContext remote = parameters.projection().isEmpty()
                ? CameraCaptureEvents.photoContext(holder, camera) : null;
        if (remote == null) {
            // 普通拍照或非远程会话：保持原行为。
            original.call(level, holder, camera, parameters);
        } else {
            // 远程拍照：用远程世界和远程观察者替换世界与持有者，
            // 后续曝光/帧生成都在远程维度语义下进行。
            original.call(remote.level(), remote, camera, parameters);
        }
    }
}

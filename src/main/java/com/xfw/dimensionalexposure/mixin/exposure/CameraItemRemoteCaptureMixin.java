package com.xfw.dimensionalexposure.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.xfw.dimensionalexposure.camera.DimensionFilmCapture;
import com.xfw.dimensionalexposure.camera.CameraCaptureTransactions;
import com.xfw.dimensionalexposure.camera.CaptureSnapshot;
import com.xfw.dimensionalexposure.camera.MobDimensionFilmCapture;
import com.xfw.dimensionalexposure.network.RemoteStandPreparation;
import com.xfw.dimensionalexposure.camera.RemoteCaptureContext;
import com.xfw.dimensionalexposure.camera.PhotoTargetContext;
import com.xfw.dimensionalexposure.util.CaptureEntitySearchRange;
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
    public abstract void dimensionalExposure$invokeTakePhoto(CameraHolder holder, ServerPlayer player,
                                                       ItemStack camera);

    /** 支架按需准备，手持先完成原生拍摄，再由上传事务处理传送。 */
    @WrapMethod(method = "takePhoto")
    private void dimensionalExposure$dimensionFilm(CameraHolder holder, ServerPlayer player,
                                             ItemStack camera, Operation<Void> original) {
        if (!CameraCaptureTransactions.begin((CameraItem) (Object) this, holder, player, camera)) {
            RemoteStandPreparation.finishOrdinaryCapture(holder);
            return;
        }
        CaptureSnapshot context = CameraCaptureTransactions.context(camera);
        try {
            if (getProjection(camera).isEmpty()
                    && RemoteStandPreparation.beginIfNeeded((CameraItem) (Object) this, holder, player, camera)) return;
            original.call(holder, player, camera);
            if (!RemoteStandPreparation.shouldDeferTeleport(holder)) {
                DimensionFilmCapture.teleportStandPlayersAfterPhoto(holder, camera);
                RemoteStandPreparation.finishOrdinaryCapture(holder);
            }
            CameraCaptureTransactions.nativeReturned(camera);
        } catch (RuntimeException exception) {
            CameraCaptureTransactions.failed(context, "执行拍摄时发生异常");
            throw exception;
        }
    }

    /** 包裹 addNewFrame 的 addFrameToFilm：曝光失效直接跳过写卷。 */
    @WrapOperation(method = "addNewFrame", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/world/item/camera/CameraItem;addFrameToFilm(Lnet/minecraft/world/item/ItemStack;Lio/github/mortuusars/exposure/world/camera/frame/Frame;)V"))
    private void dimensionalExposure$commitAfterScreenshot(CameraItem item, ItemStack camera, Frame frame,
                                                      Operation<Void> original) {
        // 仍执行后面的 onFrameAdded，保留统计、进度和实体被拍行为。
        if (CameraCaptureTransactions.discardsImage(camera)) return;
        if (!RemoteStandPreparation.deferFrameCommit(camera, frame)) {
            original.call(item, camera, frame);
        }
    }

    /** 按服务端半径查询远景生物，红石照片保留原维度真实实体。 */
    @WrapOperation(method = "addNewFrame", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/world/camera/frame/EntitiesInFrame;get(Lio/github/mortuusars/exposure/world/entity/CameraHolder;Lio/github/mortuusars/exposure/util/PointOfView;D)Ljava/util/List;"))
    private List<LivingEntity> dimensionalExposure$mobCaptureRange(CameraHolder holder, PointOfView view,
            double fov, Operation<List<LivingEntity>> original, @Local(argsOnly = true) ItemStack camera) {
        Integer radius = holder instanceof RemoteCaptureContext && CameraCaptureTransactions.transfersMobs(camera)
                ? CameraCaptureTransactions.mobRadius(camera) : null;
        List<LivingEntity> entities = CaptureEntitySearchRange.withRadius(radius,
                () -> original.call(holder, view, fov));
        // 传送名单独立查询；此处不向照片添加投影玩家元数据。
        return CameraCaptureTransactions.photoSubjects(holder, camera, view, fov, radius, entities);
    }

    /** 将支架快门关闭动作延迟到截图完成。 */
    @WrapMethod(method = "onShutterClosed")
    private void dimensionalExposure$finishShutterAfterScreenshot(CameraHolder holder, ServerLevel level,
                                                            ItemStack camera, Operation<Void> original) {
        if (!RemoteStandPreparation.deferShutterClosed(holder, () -> original.call(holder, level, camera))) {
            original.call(holder, level, camera);
        }
    }

    /** 包裹 takePhoto 的 ExposureRepository.expect：曝光失效不期待上传。 */
    @WrapOperation(method = "takePhoto", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/world/level/storage/ExposureRepository;expect(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/String;)V"))
    private void dimensionalExposure$mobFilmUploadCallback(ExposureRepository repository, ServerPlayer player,
                                                     String exposureId, Operation<Void> original,
                                                     @Local(argsOnly = true) ItemStack camera) {
        if (CameraCaptureTransactions.discardsImage(camera)) return;
        if (!MobDimensionFilmCapture.expectUpload(repository, player, exposureId)) {
            original.call(repository, player, exposureId);
        }
    }

    /** 曝光失效时跳过截图请求，直接完成无需上传的生物事务。 */
    @WrapOperation(method = "takePhoto", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/network/Packets;sendToClient(Lio/github/mortuusars/exposure/network/packet/Packet;Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void dimensionalExposure$discardExposureImage(Packet packet, ServerPlayer player,
                                                   Operation<Void> original,
                                                   @Local(argsOnly = true) ItemStack camera) {
        if (CameraCaptureTransactions.discardsImage(camera)) {
            MobDimensionFilmCapture.completeWithoutUpload(player,
                    ((CaptureStartS2CP) packet).captureParameters().exposureId());
        } else {
            original.call(packet, player);
        }
    }

    /** 将截图参数交给支架事务补充场景和曝光编号。 */
    @ModifyArg(method = "takePhoto", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/network/packet/clientbound/CaptureStartS2CP;<init>(Lnet/minecraft/resources/ResourceLocation;Lio/github/mortuusars/exposure/world/camera/capture/CaptureParameters;)V"), index = 1)
    private CaptureParameters dimensionalExposure$standCaptureScene(CaptureParameters parameters,
            @Local(argsOnly = true) CameraHolder holder,
            @Local(argsOnly = true) ServerPlayer player,
            @Local(argsOnly = true) ItemStack camera) {
        return RemoteStandPreparation.captureParameters(holder, player, camera, parameters);
    }

    /** 保留来源标签并准备胶卷事务，不向照片插入虚拟玩家元数据。 */
    @Inject(method = "createFrame", at = @At("RETURN"), cancellable = true)
    private void dimensionalExposure$prepareFrame(
            CameraHolder holder, ServerLevel level, ItemStack camera,
            CaptureParameters parameters, List<BlockPos> positions,
            List<LivingEntity> entities,
            CallbackInfoReturnable<Frame> callback) {
        Frame frame = PhotoTargetContext.withSourceMetadata(
                callback.getReturnValue(), CameraCaptureTransactions.context(camera));
        CameraCaptureTransactions.prepareFrame(camera, frame);
        callback.setReturnValue(frame);
    }

    /** Shadow 声明原 getProjection，用于让现有投影照片避开维度相机替换。 */
    @Shadow
    protected abstract Optional<Projection> getProjection(ItemStack camera);

    /** 远景拍摄采用目标维度光照，普通拍摄采用源世界光照。 */
    @Redirect(method = "takePhoto", at = @At(value = "INVOKE",
            target = "Lio/github/mortuusars/exposure/world/level/LevelUtil;getLightLevelAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)I"))
    private int dimensionalExposure$targetLight(Level level, BlockPos position,
                                          CameraHolder holder, ServerPlayer player, ItemStack camera) {
        // 若相机带有投影（如望远镜），则不做远程捕获，保持原逻辑；
        // 否则尝试解析远程捕获上下文（需要玩家、会话、滤镜均匹配）。
        RemoteCaptureContext remote = getProjection(camera).isEmpty()
                ? CameraCaptureTransactions.photoContext(holder, camera) : null;
        // 远程上下文存在时，使用远程维度与远程观察者位置的光照；
        // 否则回退到原始世界与位置。
        return remote == null ? LevelUtil.getLightLevelAt(level, position)
                : LevelUtil.getLightLevelAt(remote.level(), remote.asHolderEntity().blockPosition());
    }

    /** 远景拍摄时替换世界和持有者，让元数据来自目标维度。 */
    @WrapMethod(method = "addNewFrame")
    private void dimensionalExposure$remoteCapture(ServerLevel level, CameraHolder holder, ItemStack camera,
                                             CaptureParameters parameters, Operation<Void> original) {
        // 若参数中已包含投影（非空），说明不是普通拍照，不做远程替换。
        // 否则尝试解析远程上下文（依赖会话、滤镜匹配等）。
        RemoteCaptureContext remote = parameters.projection().isEmpty()
                ? CameraCaptureTransactions.photoContext(holder, camera) : null;
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

package com.xfw.shuttershadow.mixin.exposure;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.xfw.shuttershadow.DimensionFilmCapture;
import com.xfw.shuttershadow.CameraEnchantments;
import com.xfw.shuttershadow.MobDimensionFilmCapture;
import com.xfw.shuttershadow.network.RemoteCameraSession;
import com.xfw.shuttershadow.network.CameraSessionCloseC2S;
import com.xfw.shuttershadow.network.RemoteSceneStartS2C;
import com.xfw.shuttershadow.network.RemoteStandPreparation;
import com.xfw.shuttershadow.RemoteCaptureContext;
import com.xfw.shuttershadow.ExposureVisibility;
import com.xfw.shuttershadow.ShuttershadowConfig;
import com.xfw.shuttershadow.util.CaptureEntitySearchRange;
import io.github.mortuusars.exposure.util.ExtraData;
import io.github.mortuusars.exposure.world.camera.frame.EntityInFrame;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.camera.capture.CaptureParameters;
import io.github.mortuusars.exposure.world.camera.capture.Projection;
import io.github.mortuusars.exposure.util.PointOfView;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import io.github.mortuusars.exposure.world.item.camera.CameraSettings;
import io.github.mortuusars.exposure.network.packet.Packet;
import io.github.mortuusars.exposure.network.packet.clientbound.CaptureStartS2CP;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.level.LevelUtil;
import io.github.mortuusars.exposure.world.level.storage.ExposureRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 将 Exposure 相机接入维度拍摄、胶卷传送和附魔流程。 */
@Mixin(value = CameraItem.class, remap = false)
public abstract class CameraItemRemoteCaptureMixin implements com.xfw.shuttershadow.DimensionFilmCapture.TakePhotoInvoker {

    /** 自恋狂相机打开时进入正面自拍并归零自拍旋转。 */
    @Inject(method = "activateInHand", at = @At("HEAD"))
    private void shuttershadow$openInSelfieMode(Player player, ItemStack camera, InteractionHand hand,
                                               CallbackInfoReturnable<InteractionResultHolder<ItemStack>> callback) {
        if (CameraEnchantments.has(camera, CameraEnchantments.NARCISSISM)) {
            CameraSettings.SELFIE_MODE.set(camera, true);
            CameraSettings.SELFIE_ROTATION_X.set(camera, 0.0D);
            CameraSettings.SELFIE_ROTATION_Y.set(camera, 0.0D);
        }
    }

    /** Invoker 暴露原 takePhoto 给内部准备事务恢复调用。 */
    @Invoker("takePhoto")
    @Override
    public abstract void shuttershadow$invokeTakePhoto(CameraHolder holder, ServerPlayer player,
                                                       ItemStack camera);

    /** 将维度自拍和支架拍摄交给对应事务准备流程。 */
    @Inject(method = "takePhoto", at = @At("HEAD"), cancellable = true)
    private void shuttershadow$dimensionFilm(CameraHolder holder, ServerPlayer player,
                                             ItemStack camera, CallbackInfo callback) {
        if ((getProjection(camera).isEmpty()
                && RemoteStandPreparation.beginIfNeeded((CameraItem) (Object) this, holder, player, camera))
                || com.xfw.shuttershadow.DimensionFilmCapture.beginIfNeeded(
                (CameraItem) (Object) this, holder, player, camera)) {
            callback.cancel();
        }
    }

    /** 没有支架延迟完成事务时执行玩家胶卷名单传送。 */
    @Inject(method = "takePhoto", at = @At("RETURN"))
    private void shuttershadow$teleportStandFilmPlayers(CameraHolder holder, ServerPlayer player,
                                                        ItemStack camera, CallbackInfo callback) {
        if (!RemoteStandPreparation.shouldDeferTeleport(holder)) {
            try {
                com.xfw.shuttershadow.DimensionFilmCapture.teleportStandPlayersAfterPhoto(holder, camera);
            } finally {
                RemoteStandPreparation.finishOrdinaryCapture(holder);
            }
        }
    }

    /** 包裹 addNewFrame 的 addFrameToFilm：曝光失效直接跳过写卷。 */
    @WrapOperation(method = "addNewFrame", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/world/item/camera/CameraItem;addFrameToFilm(Lnet/minecraft/world/item/ItemStack;Lio/github/mortuusars/exposure/world/camera/frame/Frame;)V"))
    private void shuttershadow$commitAfterScreenshot(CameraItem item, ItemStack camera, Frame frame,
                                                      Operation<Void> original) {
        // 仍执行后面的 onFrameAdded：保留统计、进度、实体及 Exposure 事件。
        if (CameraEnchantments.has(camera, CameraEnchantments.EXPOSURE_FAILURE)) return;
        if (!RemoteStandPreparation.deferFrameCommit(camera, frame)) original.call(item, camera, frame);
    }

    /** 按服务端半径查询远景生物，并从红石照片中移除玩家。 */
    @WrapOperation(method = "addNewFrame", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/world/camera/frame/EntitiesInFrame;get(Lio/github/mortuusars/exposure/world/entity/CameraHolder;Lio/github/mortuusars/exposure/util/PointOfView;D)Ljava/util/List;"))
    private List<LivingEntity> shuttershadow$mobCaptureRange(CameraHolder holder, PointOfView view,
            double fov, Operation<List<LivingEntity>> original, @Local(argsOnly = true) ItemStack camera) {
        Integer radius = holder instanceof RemoteCaptureContext && MobDimensionFilmCapture.hasMobDimensionFilm(camera)
                ? ShuttershadowConfig.mobCaptureRadius() : null;
        List<LivingEntity> entities = CaptureEntitySearchRange.withRadius(radius,
                () -> original.call(holder, view, fov));
        // 红石源照片不记录玩家；传送名单由独立的源视锥资格查询决定。
        return holder.asHolderEntity() instanceof CameraStandEntity stand
                && RemoteStandPreparation.isRedstoneCapture(stand)
                ? entities.stream().filter(entity -> !(entity instanceof Player)).toList() : entities;
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
        if (CameraEnchantments.has(camera, CameraEnchantments.EXPOSURE_FAILURE)) return;
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
        if (CameraEnchantments.has(camera, CameraEnchantments.EXPOSURE_FAILURE)) {
            MobDimensionFilmCapture.completeWithoutUpload(player,
                    ((CaptureStartS2CP) packet).captureParameters().exposureId());
        } else {
            original.call(packet, player);
        }
    }

    /** 修改 CaptureStartS2CP 构造的参数：曝光失效只登记曝光 ID。 */
    @ModifyArg(method = "takePhoto", at = @At(value = "INVOKE", target =
            "Lio/github/mortuusars/exposure/network/packet/clientbound/CaptureStartS2CP;<init>(Lnet/minecraft/resources/ResourceLocation;Lio/github/mortuusars/exposure/world/camera/capture/CaptureParameters;)V"), index = 1)
    private CaptureParameters shuttershadow$standCaptureScene(CaptureParameters parameters,
            @Local(argsOnly = true) CameraHolder holder,
            @Local(argsOnly = true) ServerPlayer player,
            @Local(argsOnly = true) ItemStack camera) {
        if (CameraEnchantments.has(camera, CameraEnchantments.EXPOSURE_FAILURE)) {
            RemoteStandPreparation.recordExposure(holder, player, camera, parameters.exposureId());
            return parameters;
        }
        if (!(holder.asHolderEntity() instanceof CameraStandEntity stand)
                || parameters.projection().isPresent()) {
            return parameters;
        }
        Long sourceSequence = RemoteStandPreparation.sourceCaptureSequence(holder, player, camera);
        if (sourceSequence != null) {
            RemoteStandPreparation.recordExposure(holder, player, camera, parameters.exposureId());
            return parameters.mutable().extraData(
                    CameraSessionCloseC2S.SOURCE_CAPTURE_SEQUENCE, sourceSequence).build();
        }
        RemoteCaptureContext remote = RemoteCaptureContext.resolve(holder, camera);
        if (remote == null) return parameters;
        RemoteStandPreparation.recordExposure(holder, player, camera, parameters.exposureId());
        RemoteSceneStartS2C scene = RemoteStandPreparation.captureScene(holder, player, camera);
        if (scene == null) scene = RemoteCameraSession.openCapture(player, remote);
        return parameters.mutable().extraData(
                RemoteSceneStartS2C.CAPTURE_SCENE, scene).build();
    }

    /** 将源维度出镜玩家补入手动支架远景照片的实体元数据。 */
    @Inject(method = "createFrame", at = @At("RETURN"), cancellable = true)
    private void shuttershadow$addProjectedStandOperator(
            CameraHolder holder, ServerLevel level, ItemStack camera,
            CaptureParameters parameters, List<BlockPos> positions,
            List<net.minecraft.world.entity.LivingEntity> entities,
            CallbackInfoReturnable<Frame> callback) {
        if (parameters.projection().isPresent()) return;

        if (!(holder instanceof RemoteCaptureContext remote)
                || remote.cameraStandId() < 0) return;

        Frame.Mutable mutable = new Frame.Mutable(callback.getReturnValue());
        List<EntityInFrame> entitiesInFrame = new ArrayList<>(mutable.getEntitiesInFrame());
        List<ServerPlayer> candidates = remote.playersInFrame(camera);
        if (!candidates.isEmpty()) {
            // 同一张照片的远景镜头参数一致，直接复用 Exposure 的查询结果。
            CameraItem cameraItem = (CameraItem) camera.getItem();
            PointOfView view = cameraItem.getPointOfView(remote, camera);
            double fov = cameraItem.getViewfinderFov(remote.level(), camera);
            for (ServerPlayer candidate : candidates) {
                addProjectedPlayer(remote, candidate, view, fov, entitiesInFrame);
            }
        }
        if (entitiesInFrame.size() > 10) {
            entitiesInFrame.subList(10, entitiesInFrame.size()).clear();
        }
        mutable.setEntitiesInFrame(entitiesInFrame);
        callback.setReturnValue(mutable.toImmutable());
    }

    /** 检查投影玩家可见性，去重后按距离插入照片实体名单。 */
    private static void addProjectedPlayer(RemoteCaptureContext remote, ServerPlayer player,
                                           PointOfView view, double fov,
                                           List<EntityInFrame> entitiesInFrame) {
        Entity projectedEntity = remote.projectedPlayer(player);
        if (!ExposureVisibility.isVisible(view, projectedEntity, fov)) return;
        ResourceLocation playerId = BuiltInRegistries.ENTITY_TYPE.getKey(
                net.minecraft.world.entity.EntityType.PLAYER);
        Vec3 targetPosition = projectedEntity.position();
        int distance = Math.max(0, Mth.floor(remote.asHolderEntity().distanceTo(projectedEntity)));
        EntityInFrame projected = new EntityInFrame(playerId, player.getName().getString(),
                BlockPos.containing(targetPosition), distance, ExtraData.EMPTY);
        if (entitiesInFrame.stream().anyMatch(entity -> playerId.equals(entity.id())
                && projected.pos().equals(entity.pos()) && projected.name().equals(entity.name()))) return;
        int insertAt = entitiesInFrame.size();
        for (int i = 0; i < entitiesInFrame.size(); i++) {
            if (entitiesInFrame.get(i).distance() > distance) {
                insertAt = i;
                break;
            }
        }
        if (insertAt < 10) entitiesInFrame.add(insertAt, projected);
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
                ? RemoteCaptureContext.resolve(holder, camera) : null;
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
                ? RemoteCaptureContext.resolve(holder, camera) : null;
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

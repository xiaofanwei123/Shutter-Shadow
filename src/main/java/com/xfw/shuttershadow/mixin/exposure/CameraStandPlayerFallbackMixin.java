package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.ExposureVisibility;
import com.xfw.shuttershadow.ShuttershadowConfig;
import io.github.mortuusars.exposure.world.entity.CameraStandEntity;
import io.github.mortuusars.exposure.world.item.camera.Attachment;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.Comparator;

/** Supplies a bounded server-side photographer for unattended dimension stands. */
@Mixin(value = CameraStandEntity.class, remap = false)
public abstract class CameraStandPlayerFallbackMixin {
    @Inject(method = "getPlayerExecutingExposure", at = @At("RETURN"), cancellable = true)
    private void shuttershadow$selectNearbyPlayer(CallbackInfoReturnable<Optional<Player>> callback) {
        CameraStandEntity stand = (CameraStandEntity) (Object) this;
        if (stand.level().isClientSide) return;
        if (stand.operator() instanceof ServerPlayer operator && operator.isAlive()
                && operator.level() == stand.level()) {
            ServerPlayer current = ((net.minecraft.server.level.ServerLevel) stand.level())
                    .getServer().getPlayerList().getPlayer(operator.getUUID());
            callback.setReturnValue(Optional.ofNullable(current != null ? current : operator));
            return;
        }
        // 执行截图的客户端与出镜/传送对象职责不同。拥有者在画面外仍能执行截图，
        // 是否出镜与是否传送统一由支架拍摄的 Exposure 视锥名单决定。
        if (callback.getReturnValue().orElse(null) instanceof ServerPlayer previous
                && previous.isAlive() && previous.level() == stand.level()) return;
        ItemStack camera = stand.getCamera();
        // Redstone calls CameraItem.release directly. At that point the stand
        // camera is normally not active, so requiring isActive would make the
        // fallback invisible exactly when the redstone shutter fires.
        if (!(camera.getItem() instanceof CameraItem)) return;
        ItemStack filter = Attachment.FILTER.get(camera).getForReading();
        if (filter.isEmpty()) return;
        DimensionFilters.Route mapping = DimensionFilters.resolve(
                ((net.minecraft.server.level.ServerLevel) stand.level()).registryAccess(),
                filter, stand.level().dimension().location());
        if (mapping == null || mapping.dimension() == null) return;
        ResourceKey<Level> target = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                mapping.dimension());
        if (target.equals(stand.level().dimension())) return;
        Optional<ServerPlayer> inFrame = ExposureVisibility.playersInFrame(stand, camera).stream()
                .filter(player -> player instanceof ServerPlayer)
                .map(player -> (ServerPlayer) player)
                .findFirst();
        if (inFrame.isPresent()) {
            callback.setReturnValue(Optional.of(inFrame.get()));
            return;
        }

        // Exposure still needs a connected client to receive the photograph,
        // even when nobody is part of the photographed frame. Use the nearest
        // living player as the executor; this player is not added to the frame
        // and is not teleported unless Exposure's own visibility check finds it.
        ServerPlayer executor = ((net.minecraft.server.level.ServerLevel) stand.level()).players().stream()
                .filter(player -> player.isAlive()
                        && stand.distanceToSqr(player)
                        <= (double) ShuttershadowConfig.standPlayerRadius()
                        * ShuttershadowConfig.standPlayerRadius())
                .min(Comparator.comparingDouble(stand::distanceToSqr))
                .orElse(null);
        callback.setReturnValue(Optional.ofNullable(executor));
    }
}

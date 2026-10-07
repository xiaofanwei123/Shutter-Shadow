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

/** 为无人操作的维度支架寻找在线拍摄执行者。 */
@Mixin(value = CameraStandEntity.class, remap = false)
public abstract class CameraStandPlayerFallbackMixin {
    /** 注入 getPlayerExecutingExposure 返回。 */
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
        // 红石直接调用 CameraItem.release，此时支架相机通常尚未激活。
        // 若要求 isActive，红石快门触发时将无法找到替代执行者。
        // 因此这里不以取景器激活状态作为筛选条件。
        if (!(camera.getItem() instanceof CameraItem)) return;
        ItemStack filter = Attachment.FILTER.get(camera).getForReading();
        if (filter.isEmpty()) return;
        DimensionFilters.Route mapping = DimensionFilters.resolve(
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

        // Exposure 仍需在线客户端接收照片，即使画面中没有玩家。
        // 使用距离最近的存活玩家执行截图。
        // 执行者不会因此自动加入照片的实体名单。
        // 只有通过 Exposure 可见性检测时，执行者才会被传送。
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

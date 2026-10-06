package com.xfw.shuttershadow;

import io.github.mortuusars.exposure.util.Fov;
import io.github.mortuusars.exposure.util.PointOfView;
import io.github.mortuusars.exposure.world.camera.frame.EntitiesInFrame;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.xfw.shuttershadow.util.CaptureEntitySearchRange;

import java.util.List;

/** 未注册到世界的投影实体无法被 get 扫描，因此直接调用 Exposure 的原生判定方法。 */
public final class ExposureVisibility {
    private ExposureVisibility() {
    }

    /** 手动和红石支架共用 Exposure 的视锥、遮挡、焦距与存活判定。 */
    public static List<Player> playersInFrame(CameraHolder holder, ItemStack camera) {
        if (!(camera.getItem() instanceof CameraItem item)) return List.of();
        int radius = ShuttershadowConfig.standPlayerRadius();
        // 查询先限于合影范围，避免对最终不会入选的 128 格内实体排序和遮挡检测。
        return CaptureEntitySearchRange.withRadius(radius, () -> EntitiesInFrame.get(
                        holder.asHolderEntity(), item.getPointOfView(holder, camera),
                        item.getViewfinderFov(holder.asHolderEntity().level(), camera))).stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .filter(player -> holder.asHolderEntity().distanceToSqr(player) <= radius * radius)
                .toList();
    }

    public static boolean isVisible(PointOfView view, Entity entity, double fov) {
        BlockPos cameraBlock = BlockPos.containing(view.pos());
        BlockPos entityBlock = entity.blockPosition();
        if (Math.abs(entityBlock.getX() - cameraBlock.getX()) > 128
                || Math.abs(entityBlock.getY() - cameraBlock.getY()) > 128
                || Math.abs(entityBlock.getZ() - cameraBlock.getZ()) > 128) {
            return false;
        }

        double effectiveFov = fov * 0.95D;
        var frustum = EntitiesInFrame.FrustumCheck.createFromCamera(
                view.pos(), view.dir(), (float) Math.toRadians(effectiveFov));
        double focalLength = Fov.fovToFocalLength(effectiveFov);
        return frustum.contains(entity.getEyePosition())
                && EntitiesInFrame.calculateVisibleDistance(view.pos(), entity) <= focalLength
                && EntitiesInFrame.hasLineOfSight(view.pos(), entity);
    }
}

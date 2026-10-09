package com.xfw.dimensionalexposure.camera;

import com.xfw.dimensionalexposure.DimensionalExposureConfig;

import io.github.mortuusars.exposure.util.Fov;
import io.github.mortuusars.exposure.util.PointOfView;
import io.github.mortuusars.exposure.world.camera.frame.EntitiesInFrame;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.xfw.dimensionalexposure.util.CaptureEntitySearchRange;

import java.util.List;
import java.util.function.Predicate;

/** 集中复用Exposure视锥、可见距离与遮挡检查。 */
public final class ExposureVisibility {
    /** 禁止实例化此工具类。 */
    private ExposureVisibility() {
    }

    /** 在临时实体搜索半径作用域内调用EntitiesInFrame，然后筛出玩家并再次以真实距离限制。 */
    public static List<Player> playersInFrame(CameraHolder holder, ItemStack camera) {
        if (!(camera.getItem() instanceof CameraItem item)) return List.of();
        int radius = DimensionalExposureConfig.cameraPlayerRadius();
        // 查询先限于合影范围，避免对最终不会入选的 128 格内实体排序和遮挡检测。
        return CaptureEntitySearchRange.withRadius(radius, () -> EntitiesInFrame.get(
                        holder.asHolderEntity(), item.getPointOfView(holder, camera),
                        item.getViewfinderFov(holder.asHolderEntity().level(), camera))).stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .filter(player -> holder.asHolderEntity().distanceToSqr(player) <= radius * radius)
                .toList();
    }

    /** 同一批候选复用95%FOV视锥和焦距，逐个保留范围、眼睛入镜及遮挡检查。 */
    public static Predicate<Entity> visibleFrom(PointOfView view, double fov) {
        BlockPos cameraBlock = BlockPos.containing(view.pos());
        double effectiveFov = fov * 0.95D;
        var frustum = EntitiesInFrame.FrustumCheck.createFromCamera(
                view.pos(), view.dir(), (float) Math.toRadians(effectiveFov));
        double focalLength = Fov.fovToFocalLength(effectiveFov);
        return entity -> {
            BlockPos entityBlock = entity.blockPosition();
            if (Math.abs(entityBlock.getX() - cameraBlock.getX()) > 128
                    || Math.abs(entityBlock.getY() - cameraBlock.getY()) > 128
                    || Math.abs(entityBlock.getZ() - cameraBlock.getZ()) > 128) return false;
            return frustum.contains(entity.getEyePosition())
                    && EntitiesInFrame.calculateVisibleDistance(view.pos(), entity) <= focalLength
                    && EntitiesInFrame.hasLineOfSight(view.pos(), entity);
        };
    }
}

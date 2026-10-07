package com.xfw.shuttershadow.camera;

import com.xfw.shuttershadow.ShuttershadowConfig;

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

/** 集中复用Exposure视锥、可见距离与遮挡检查。 */
public final class ExposureVisibility {
    /** 禁止实例化此工具类。 */
    private ExposureVisibility() {
    }

    /** 在临时实体搜索半径作用域内调用EntitiesInFrame，然后筛出玩家并再次以真实距离限制。 */
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

    /** 先排除相机与实体各轴距离超过128的候选，再以95%FOV创建视锥，检查眼睛入镜、焦距可见距离及无遮挡。 */
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

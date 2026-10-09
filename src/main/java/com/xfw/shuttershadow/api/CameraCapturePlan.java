package com.xfw.shuttershadow.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** 拍摄前可修改、受理后冻结的成片和传送计划。 */
public final class CameraCapturePlan {
    /** 正常成片或仅执行拍摄行为、不写卷也不生成图片。 */
    public enum PhotoOutput { PHOTO, NO_IMAGE }
    private PhotoOutput output;
    private ResourceLocation photoDimension;
    private Vec3 photoPosition;
    private boolean photoSceneChanged;
    private ResourceLocation mobQueryDimension;
    private boolean playerTransfer;
    private boolean mobTransfer;
    private int mobRadius;
    private ResourceLocation playerDimension;
    private Vec3 playerPosition;
    private ResourceLocation mobDimension;
    private Vec3 mobPosition;
    private ResourceLocation filterShader;
    private boolean shaderChanged;
    private boolean frozen;

    /** 建立当前玩法的默认计划。 */
    public CameraCapturePlan(PhotoOutput output, ResourceLocation photoDimension,
            @Nullable ResourceLocation observationDimension, boolean playerTransfer, boolean mobTransfer,
            int mobRadius, ResourceLocation sourceDimension) {
        this.output = output;
        this.photoDimension = photoDimension;
        this.playerTransfer = playerTransfer;
        this.mobTransfer = mobTransfer;
        this.mobRadius = mobRadius;
        playerDimension = observationDimension;
        mobQueryDimension = observationDimension;
        mobDimension = sourceDimension;
    }
    /** 冻结计划，防止异步等待期间被旧事件修改。 */
    public void freeze() { frozen = true; }
    /** 拒绝修改已受理计划。 */
    private void mutable() { if (frozen) throw new IllegalStateException("拍摄计划已经冻结"); }
    /** 验证坐标有限，不允许异常数值进入渲染和传送。 */
    private static Vec3 position(@Nullable Vec3 position) {
        if (position != null && (!Double.isFinite(position.x) || !Double.isFinite(position.y)
                || !Double.isFinite(position.z))) throw new IllegalArgumentException("坐标必须有限");
        return position;
    }
    /** 设置成片策略，免成片仍保留拍摄事件及传送。 */
    public void setPhotoOutput(PhotoOutput output) { mutable(); this.output = Objects.requireNonNull(output); }
    /** 设置照片世界和镜头脚底坐标，空坐标沿用原生维度比例。 */
    public void setPhotoScene(ResourceLocation dimension, @Nullable Vec3 position) {
        mutable(); photoDimension = Objects.requireNonNull(dimension); photoPosition = position(position); photoSceneChanged = true;
    }
    /** 设置图片滤镜，空值表示不使用滤镜。 */
    public void setFilterShader(@Nullable ResourceLocation shader) { mutable(); filterShader = shader; shaderChanged = true; }
    /** 设置是否传送玩家，自拍只作用于本人。 */
    public void setPlayerTransfer(boolean enabled) { mutable(); playerTransfer = enabled; }
    /** 设置是否传送选中的目标生物。 */
    public void setMobTransfer(boolean enabled) { mutable(); mobTransfer = enabled; }
    /** 设置生物选择范围，单位为方块。 */
    public void setMobCaptureRadius(int radius) {
        mutable(); if (radius < 1 || radius > 32) throw new IllegalArgumentException("生物范围应为1至32格");
        mobRadius = radius;
    }
    /** 设置查询生物的维度，独立于照片来源。 */
    public void setMobQueryDimension(ResourceLocation dimension) { mutable(); mobQueryDimension = Objects.requireNonNull(dimension); }
    /** 设置玩家目的地，空坐标为每名玩家按原生维度比例换算。 */
    public void setPlayerDestination(ResourceLocation dimension, @Nullable Vec3 position) {
        mutable(); playerDimension = Objects.requireNonNull(dimension); playerPosition = position(position);
    }
    /** 设置生物目的地，空坐标沿用从查询世界返回来源镜头的坐标映射。 */
    public void setMobDestination(ResourceLocation dimension, @Nullable Vec3 position) {
        mutable(); mobDimension = Objects.requireNonNull(dimension); mobPosition = position(position);
    }
    /** 返回是否已冻结。 */
    public boolean isFrozen() { return frozen; }
    /** 返回成片策略。 */
    public PhotoOutput getPhotoOutput() { return output; }
    /** 返回照片维度。 */
    public ResourceLocation getPhotoDimension() { return photoDimension; }
    /** 返回自定义镜头位置。 */
    public @Nullable Vec3 getPhotoPosition() { return photoPosition; }
    /** 返回是否明确覆盖照片场景。 */
    public boolean isPhotoSceneChanged() { return photoSceneChanged; }
    /** 返回是否传送玩家。 */
    public boolean isPlayerTransfer() { return playerTransfer; }
    /** 返回是否传送生物。 */
    public boolean isMobTransfer() { return mobTransfer; }
    /** 返回生物查询范围。 */
    public int getMobCaptureRadius() { return mobRadius; }
    /** 返回生物查询维度。 */
    public @Nullable ResourceLocation getMobQueryDimension() { return mobQueryDimension; }
    /** 返回玩家目标维度。 */
    public @Nullable ResourceLocation getPlayerDimension() { return playerDimension; }
    /** 返回固定玩家目的地，空值使用坐标映射。 */
    public @Nullable Vec3 getPlayerPosition() { return playerPosition; }
    /** 返回生物目标维度。 */
    public ResourceLocation getMobDimension() { return mobDimension; }
    /** 返回固定生物目的地。 */
    public @Nullable Vec3 getMobPosition() { return mobPosition; }
    /** 返回是否覆盖照片滤镜。 */
    public boolean isShaderChanged() { return shaderChanged; }
    /** 返回覆盖的照片滤镜。 */
    public @Nullable ResourceLocation getFilterShader() { return filterShader; }
}

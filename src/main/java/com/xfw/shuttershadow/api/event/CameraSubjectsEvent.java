package com.xfw.shuttershadow.api.event;

import com.xfw.shuttershadow.api.CameraCaptureContext;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.Event;

import java.util.List;

/** 分别选择照片实体和待传送主体，不把红石照片名单当作传送名单。 */
public final class CameraSubjectsEvent extends Event {
    /** 对象查询阶段，避免多次查询被误认为同一名单。 */
    public enum Purpose { PHOTO, PLAYER_TRANSFER, MOB_TRANSFER }
    private final CameraCaptureContext context;
    private final Purpose purpose;
    private final List<LivingEntity> candidates;
    private List<LivingEntity> subjects;
    /** 保存可见候选和默认选择，监听者只能从候选中选择。 */
    public CameraSubjectsEvent(CameraCaptureContext context, Purpose purpose,
                               List<? extends LivingEntity> candidates, List<? extends LivingEntity> selected) {
        this.context = context; this.purpose = purpose;
        this.candidates = List.copyOf(candidates);
        select(selected);
    }
    /** 返回拍摄上下文。 */
    public CameraCaptureContext getContext() { return context; }
    /** 返回当前名单用途。 */
    public Purpose getPurpose() { return purpose; }
    /** 返回经过当前玩法查询的候选，不额外加载玩家目的地区块。 */
    public List<LivingEntity> getCandidates() { return candidates; }
    /** 返回照片阶段名单，其余阶段为空。 */
    public List<LivingEntity> getPhotoSubjects() { return purpose == Purpose.PHOTO ? subjects : List.of(); }
    /** 返回传送阶段名单，照片阶段为空。 */
    public List<LivingEntity> getTransferSubjects() { return purpose != Purpose.PHOTO ? subjects : List.of(); }
    /** 筛选照片实体，不能把传送名单写入照片阶段。 */
    public void setPhotoSubjects(List<? extends LivingEntity> subjects) {
        if (purpose != Purpose.PHOTO) throw new IllegalStateException("当前不是照片对象阶段");
        select(subjects);
    }
    /** 筛选待传送主体，不能把照片名单写入传送阶段。 */
    public void setTransferSubjects(List<? extends LivingEntity> subjects) {
        if (purpose == Purpose.PHOTO) throw new IllegalStateException("当前不是传送对象阶段");
        select(subjects);
    }
    /** 去重并限制为本次有效候选。 */
    private void select(List<? extends LivingEntity> selected) {
        subjects = selected.stream().filter(candidates::contains).distinct().map(entity -> (LivingEntity) entity).toList();
    }
    /** 返回本阶段最终选择。 */
    public List<LivingEntity> getSubjects() { return subjects; }
}

package com.xfw.shuttershadow.mixin.exposure;

import com.xfw.shuttershadow.network.RemoteStandPreparation;
import io.github.mortuusars.exposure.util.UnixTimestamp;
import io.github.mortuusars.exposure.world.level.storage.ExpectedExposure;
import io.github.mortuusars.exposure.world.level.storage.ExposureRepository;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Set;

/** 提供延长照片上传授权和清理旧授权的事务接口。 */
@Mixin(value = ExposureRepository.class, remap = false)
public abstract class ExposureRepositoryRemoteMixin implements RemoteStandPreparation.UploadWindow {
    @Shadow @Final
    protected Map<ServerPlayer, Set<ExpectedExposure>> expectedExposures;

    /** 刷新指定照片的上传等待期限，并保留原完成回调。 */
    @Override
    public void shuttershadow$refreshExpected(ServerPlayer player, String id) {
        Set<ExpectedExposure> entries = expectedExposures.get(player);
        if (entries == null) return;
        ExpectedExposure previous = entries.stream().filter(entry -> entry.id().equals(id))
                .findFirst().orElse(null);
        if (previous == null) return;
        // expect() 会新增 HashSet 条目；替换才不会留下过期的同 ID 授权或重复回调。
        entries.remove(previous);
        entries.add(new ExpectedExposure(id, UnixTimestamp.Seconds.fromNow(
                ExposureRepository.EXPECTED_TIMEOUT_SECONDS), previous.onReceived()));
    }

    /** 删除该玩家指定曝光 ID 的授权，列表空时移除玩家键。 */
    @Override
    public void shuttershadow$cancelExpected(ServerPlayer player, String exposureId) {
        Set<ExpectedExposure> entries = expectedExposures.get(player);
        if (entries == null) return;
        entries.removeIf(entry -> entry.id().equals(exposureId));
        if (entries.isEmpty()) expectedExposures.remove(player, entries);
    }
}

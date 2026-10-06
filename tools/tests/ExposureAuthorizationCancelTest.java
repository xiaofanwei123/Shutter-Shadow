import com.xfw.shuttershadow.mixin.exposure.ExposureRepositoryRemoteMixin;
import io.github.mortuusars.exposure.world.level.storage.ExpectedExposure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** 执行生产取消代码，确保拒拍只撤销该照片的上传授权，不影响同玩家的其他照片。 */
public final class ExposureAuthorizationCancelTest {
    private static int checks;

    public static void main(String[] args) {
        var repository = new Repository();
        repository.shuttershadow$cancelExpected(null, "missing");
        check(repository.expectedExposuresEmpty(), "missing player bucket must not be created");
        var denied = new ExpectedExposure("denied", 100, (player, id) -> {});
        var duplicate = new ExpectedExposure("denied", 200, (player, id) -> {});
        var unrelated = new ExpectedExposure("unrelated", 300, (player, id) -> {});
        repository.add(denied, duplicate, unrelated);
        repository.shuttershadow$cancelExpected(null, "denied");
        check(repository.remaining().equals(List.of(unrelated)), "all denied callbacks removed, unrelated upload preserved");
        check(repository.remaining().getFirst().onReceived() == unrelated.onReceived(), "unrelated callback identity preserved");
        repository.shuttershadow$cancelExpected(null, "denied");
        check(repository.remaining().equals(List.of(unrelated)), "repeated cancellation is harmless");
        repository.shuttershadow$cancelExpected(null, "unrelated");
        check(repository.expectedExposuresEmpty(), "last cancellation removes empty player bucket");
        repository.shuttershadow$refreshExpected(null, "denied");
        check(repository.expectedExposuresEmpty(), "refresh must not restore canceled authorization");
        System.out.println("Exposure authorization cancellation checks passed: " + checks);
    }

    private static final class Repository extends ExposureRepositoryRemoteMixin {
        Repository() { expectedExposures = new HashMap<>(); }
        // 此生产方法只使用 map 键，测试用 null 键避免创建服务器玩家。
        void add(ExpectedExposure... entries) { expectedExposures.put(null, new HashSet<>(List.of(entries))); }
        List<ExpectedExposure> remaining() { return List.copyOf(expectedExposures.get(null)); }
        boolean expectedExposuresEmpty() { return expectedExposures.isEmpty(); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

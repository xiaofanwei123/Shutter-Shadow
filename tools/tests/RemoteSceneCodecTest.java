import com.xfw.shuttershadow.network.CameraSessionCloseC2S;
import com.xfw.shuttershadow.network.CameraSessionRequestC2S;
import com.xfw.shuttershadow.network.RemoteSceneStartS2C;
import io.github.mortuusars.exposure.util.ExtraData;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 手动远景保留场景与投影名单；红石原维度截图仅传事务编号和完成结果。 */
public class RemoteSceneCodecTest {
    private static int checks;

    public static void main(String[] args) {
        for (long sequence : new long[]{17, -17, Long.MIN_VALUE}) {
            var players = new ArrayList<>(List.of(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")));
            var original = new RemoteSceneStartS2C(sequence,
                    ResourceLocation.parse("minecraft:the_end"), new Vec3(1, 2, 3),
                    new Vec3(4, 5, 6), 8, 32, ResourceLocation.parse("minecraft:overworld"), players);
            players.clear();
            check(original.projectedPlayers().size() == 1, "scene must retain its own player list");
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                RemoteSceneStartS2C.STREAM_CODEC.encode(buf, original);
                check(original.equals(RemoteSceneStartS2C.STREAM_CODEC.decode(buf)), "manual scene network round trip");
                check(buf.readableBytes() == 0, "unread scene bytes after decode");
            } finally {
                buf.release();
            }
            ExtraData data = new ExtraData();
            data.put(RemoteSceneStartS2C.CAPTURE_SCENE, original);
            check(original.equals(data.get(RemoteSceneStartS2C.CAPTURE_SCENE).orElseThrow()), "manual scene metadata round trip");
            var projected = new RemoteSceneStartS2C(original.sequence(), original.dimension(),
                    original.position(), original.sourceOrigin(), original.coordinateScale(),
                    original.maxRenderDistance(), original.sourceDimension(), List.of());
            check(original.withProjectedPlayers(List.of()).equals(projected),
                    "projected player replacement changed other scene fields");
            var metadata = data.getCompound(RemoteSceneStartS2C.CAPTURE_SCENE.key());
            check(!metadata.contains("capture_radius"), "removed strict radius still encoded");
            check(!metadata.contains("dimension_view_shaders"), "removed shader option still encoded");
            metadata.putInt("capture_radius", 32);
            metadata.putBoolean("dimension_view_shaders", true);
            check(original.equals(data.get(RemoteSceneStartS2C.CAPTURE_SCENE).orElseThrow()),
                    "obsolete redstone metadata altered manual scene");

            ExtraData source = new ExtraData();
            source.put(CameraSessionCloseC2S.SOURCE_CAPTURE_SEQUENCE, sequence);
            check(source.get(CameraSessionCloseC2S.SOURCE_CAPTURE_SEQUENCE).orElseThrow() == sequence,
                    "redstone source transaction metadata round trip");
            check(!source.contains(RemoteSceneStartS2C.CAPTURE_SCENE.key()),
                    "source capture must not encode a target scene");
            check(!data.contains(CameraSessionCloseC2S.SOURCE_CAPTURE_SEQUENCE.key()),
                    "manual target capture must not imply a redstone source transaction");
            for (boolean captured : new boolean[]{false, true}) {
                var completion = new CameraSessionCloseC2S(sequence, captured);
                FriendlyByteBuf result = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    CameraSessionCloseC2S.STREAM_CODEC.encode(result, completion);
                    check(sequence == result.readVarLong() && captured == result.readBoolean(),
                            "completion wire fields must be transaction and result only");
                    check(result.readableBytes() == 0, "obsolete gate flag remains on the wire");
                    result.readerIndex(0);
                    check(completion.equals(CameraSessionCloseC2S.STREAM_CODEC.decode(result)),
                            "capture completion network round trip");
                } finally {
                    result.release();
                }
            }
        }
        check(new CameraSessionCloseC2S(-17).equals(new CameraSessionCloseC2S(-17, false)),
                "close-only constructor must not acknowledge capture success");
        for (long sequence : new long[]{17, -17, Long.MIN_VALUE}) {
            for (String dimension : new String[]{"minecraft:the_end", "minecraft:the_nether"}) {
                for (int standId : new int[]{-1, 17}) {
                    var request = new CameraSessionRequestC2S(sequence,
                            ResourceLocation.parse("shuttershadow:dimension_filter"),
                            ResourceLocation.parse(dimension), standId);
                    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                    try {
                        CameraSessionRequestC2S.STREAM_CODEC.encode(buffer, request);
                        check(buffer.readVarLong() == sequence && buffer.readResourceLocation().equals(request.filterId())
                                        && buffer.readResourceLocation().equals(request.targetDimension()) && buffer.readVarInt() == standId,
                                "request wire identity includes the expected target after the shared filter ID");
                        check(buffer.readableBytes() == 0, "request has no extra or missing wire fields");
                        buffer.readerIndex(0);
                        check(CameraSessionRequestC2S.STREAM_CODEC.decode(buffer).equals(request),
                                "target-bound request survives actual FriendlyByteBuf round trip");
                    } finally {
                        buffer.release();
                    }
                }
            }
        }
        System.out.println("Manual scene and source capture codec checks passed: " + checks);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

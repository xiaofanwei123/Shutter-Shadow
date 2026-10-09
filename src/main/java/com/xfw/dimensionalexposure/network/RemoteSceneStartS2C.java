package com.xfw.dimensionalexposure.network;

import com.xfw.dimensionalexposure.DimensionalExposure;
import io.github.mortuusars.exposure.util.ExtraData;
import io.github.mortuusars.exposure.util.PointOfView;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** 固定滤镜目标的场景快照，保留来源原点和原生维度坐标映射。 */
public record RemoteSceneStartS2C(long sequence,
                                  ResourceLocation dimension,
                                  Vec3 position,
                                  Vec3 sourceOrigin,
                                  double coordinateScale,
                                  int maxRenderDistance,
                                  int playerRadius,
                                  ResourceLocation sourceDimension)
        implements CustomPacketPayload {

    /** 曝光时固定的目标镜头，只随截图请求传输，避免延迟截图读取后来更换的自拍设置。 */
    public static final ExtraData.Type<PointOfView> CAPTURE_VIEW = new ExtraData.Type<>(
            "dimensional_exposure_capture_view",
            (data, key) -> {
                ExtraData view = new ExtraData(data.getCompound(key));
                return new PointOfView(view.getOrDefault(ExtraData.Type.vec3("position"), Vec3.ZERO),
                        view.getOrDefault(ExtraData.Type.vec3("direction"), new Vec3(0, 0, 1)));
            },
            (data, key, view) -> {
                ExtraData tag = new ExtraData();
                tag.put(ExtraData.Type.vec3("position"), view.pos());
                tag.put(ExtraData.Type.vec3("direction"), view.dir());
                data.put(key, tag);
            });

    /** 仅随 Exposure 截图请求传输，不写入照片帧；手动支架照片使用独立的临时会话。 */
    public static final ExtraData.Type<RemoteSceneStartS2C> CAPTURE_SCENE = new ExtraData.Type<>(
            "dimensional_exposure_capture_scene",
            (data, key) -> {
                ExtraData scene = new ExtraData(data.getCompound(key));
                return new RemoteSceneStartS2C(scene.getLong("sequence"),
                        ResourceLocation.parse(scene.getString("dimension")),
                        scene.getOrDefault(ExtraData.Type.vec3("position"), Vec3.ZERO),
                        scene.getOrDefault(ExtraData.Type.vec3("source_origin"), Vec3.ZERO),
                        scene.getDouble("scale"), scene.getInt("distance"), scene.getInt("player_radius"),
                        ResourceLocation.parse(scene.getString("source_dimension")));
            },
            (data, key, scene) -> {
                ExtraData tag = new ExtraData();
                tag.putLong("sequence", scene.sequence());
                tag.putString("dimension", scene.dimension().toString());
                tag.put(ExtraData.Type.vec3("position"), scene.position());
                tag.put(ExtraData.Type.vec3("source_origin"), scene.sourceOrigin());
                tag.putDouble("scale", scene.coordinateScale());
                tag.putInt("distance", scene.maxRenderDistance());
                tag.putInt("player_radius", scene.playerRadius());
                tag.putString("source_dimension", scene.sourceDimension().toString());
                data.put(key, tag);
            });

    /** 包类型标识：命名空间 + 路径，注册到 NeoForge 网络层。 */
    public static final Type<RemoteSceneStartS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimensionalExposure.MODID, "remote_scene_start"));

    /**
     * 流编解码器。使用 {@code StreamCodec.of(encode, decode)} 静态构造，
     * 相比旧版 PacketBuffer 写法更类型安全，并由 NeoForge 自动处理注册。
     */
    public static final StreamCodec<FriendlyByteBuf, RemoteSceneStartS2C> STREAM_CODEC = StreamCodec.of(
            RemoteSceneStartS2C::encode, RemoteSceneStartS2C::decode);

    /** 按固定顺序编码全部场景字段。 */
    private static void encode(FriendlyByteBuf buf, RemoteSceneStartS2C value) {
        buf.writeVarLong(value.sequence());           // 变长 long：sequence 通常较小，省字节
        buf.writeResourceLocation(value.dimension()); // 维度 ID
        buf.writeVec3(value.position());              // 远程基准位置（3 个 double）
        buf.writeVec3(value.sourceOrigin());          // 源世界原点（3 个 double）
        buf.writeDouble(value.coordinateScale());     // 水平缩放系数
        buf.writeVarInt(value.maxRenderDistance());   // 服务端上限
        buf.writeVarInt(value.playerRadius());        // 来源玩家半径
        buf.writeResourceLocation(value.sourceDimension());
    }

    /** 按encode相同顺序解码全部字段。 */
    private static RemoteSceneStartS2C decode(FriendlyByteBuf buf) {
        return new RemoteSceneStartS2C(
                buf.readVarLong(),
                buf.readResourceLocation(),
                buf.readVec3(),
                buf.readVec3(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readResourceLocation());
    }

    /** 返回remote_scene_start类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

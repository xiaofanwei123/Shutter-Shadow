package com.xfw.shuttershadow.network;

import com.xfw.shuttershadow.Shuttershadow;
import io.github.mortuusars.exposure.util.ExtraData;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端：启动（或重定向）远程场景渲染。
 * <p>
 * 不改变玩家真实所在维度；远程世界的加载、网格与实体更新全部由 Immersive Portals 负责。
 * <p>
 * 设计要点：服务端只下发"基准位置 + 坐标缩放"，客户端在此基础上叠加玩家本地位移，
 * 从而无需每 tick 同步玩家坐标，远程视角即可实时跟随。
 *
 * @param sequence        会话序号，与客户端 {@code nextSequence} 对齐，防止过期包污染新会话
 * @param dimension       要渲染的远程维度 ID（例如 {@code minecraft:the_nether}）
 * @param position        远程相机在目标维度中的基准位置（已按缩放算好）
 * @param sourceOrigin    源世界原点基准，与玩家眼位相减得到本地位移
 * @param coordinateScale 水平坐标缩放系数（来自 {@code DimensionFilters.horizontalScale()}）
 * @param maxRenderDistance 服务端允许的远程最大渲染距离（单位与 IP 的 WorldRenderInfo 一致）
 */
public record RemoteSceneStartS2C(long sequence,
                                  ResourceLocation dimension,
                                  Vec3 position,
                                  Vec3 sourceOrigin,
                                  double coordinateScale,
                                  int maxRenderDistance,
                                  ResourceLocation sourceDimension,
                                  List<UUID> projectedPlayers)
        implements CustomPacketPayload {

    public RemoteSceneStartS2C {
        projectedPlayers = List.copyOf(projectedPlayers);
    }

    /** Exposure 资格名单只供本次截图等待实体同步；模型显示另由目标视锥决定。 */
    public RemoteSceneStartS2C withProjectedPlayers(List<UUID> players) {
        return new RemoteSceneStartS2C(sequence, dimension, position, sourceOrigin, coordinateScale,
                maxRenderDistance, sourceDimension, players);
    }

    /** 仅随 Exposure 截图请求传输，不写入照片帧；手动支架照片使用独立的临时会话。 */
    public static final ExtraData.Type<RemoteSceneStartS2C> CAPTURE_SCENE = new ExtraData.Type<>(
            "shuttershadow_capture_scene",
            (data, key) -> {
                ExtraData scene = new ExtraData(data.getCompound(key));
                return new RemoteSceneStartS2C(scene.getLong("sequence"),
                        ResourceLocation.parse(scene.getString("dimension")),
                        scene.getOrDefault(ExtraData.Type.vec3("position"), Vec3.ZERO),
                        scene.getOrDefault(ExtraData.Type.vec3("source_origin"), Vec3.ZERO),
                        scene.getDouble("scale"), scene.getInt("distance"),
                        ResourceLocation.parse(scene.getString("source_dimension")),
                        scene.getList("projected_players", Tag.TAG_INT_ARRAY).stream()
                                .map(NbtUtils::loadUUID).toList());
            },
            (data, key, scene) -> {
                ExtraData tag = new ExtraData();
                tag.putLong("sequence", scene.sequence());
                tag.putString("dimension", scene.dimension().toString());
                tag.put(ExtraData.Type.vec3("position"), scene.position());
                tag.put(ExtraData.Type.vec3("source_origin"), scene.sourceOrigin());
                tag.putDouble("scale", scene.coordinateScale());
                tag.putInt("distance", scene.maxRenderDistance());
                tag.putString("source_dimension", scene.sourceDimension().toString());
                ListTag players = new ListTag();
                scene.projectedPlayers().forEach(player -> players.add(NbtUtils.createUUID(player)));
                tag.put("projected_players", players);
                data.put(key, tag);
            });

    /** 包类型标识：命名空间 + 路径，注册到 NeoForge 网络层。 */
    public static final Type<RemoteSceneStartS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Shuttershadow.MODID, "remote_scene_start"));

    /**
     * 流编解码器。使用 {@code StreamCodec.of(encode, decode)} 静态构造，
     * 相比旧版 PacketBuffer 写法更类型安全，并由 NeoForge 自动处理注册。
     */
    public static final StreamCodec<FriendlyByteBuf, RemoteSceneStartS2C> STREAM_CODEC = StreamCodec.of(
            RemoteSceneStartS2C::encode, RemoteSceneStartS2C::decode);

    /**
     * 序列化。对可能较小的整数使用变长编码以节省带宽。
     */
    private static void encode(FriendlyByteBuf buf, RemoteSceneStartS2C value) {
        buf.writeVarLong(value.sequence());           // 变长 long：sequence 通常较小，省字节
        buf.writeResourceLocation(value.dimension()); // 维度 ID
        buf.writeVec3(value.position());              // 远程基准位置（3 个 double）
        buf.writeVec3(value.sourceOrigin());          // 源世界原点（3 个 double）
        buf.writeDouble(value.coordinateScale());     // 水平缩放系数
        buf.writeVarInt(value.maxRenderDistance());   // 服务端上限
        buf.writeResourceLocation(value.sourceDimension());
        buf.writeCollection(value.projectedPlayers(), (buffer, player) -> buffer.writeUUID(player));
    }

    /**
     * 反序列化。字段顺序必须与 {@link #encode} 严格一致。
     */
    private static RemoteSceneStartS2C decode(FriendlyByteBuf buf) {
        return new RemoteSceneStartS2C(
                buf.readVarLong(),
                buf.readResourceLocation(),
                buf.readVec3(),
                buf.readVec3(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readResourceLocation(),
                buf.readList(buffer -> buffer.readUUID()));
    }

    /** 返回包类型，供 NeoForge 网络层分发。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

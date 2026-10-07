# Shuttershadow API 与扩展 Wiki

本文面向接入其他模组、制作数据包和调整服务器的维护者。对应 Minecraft **1.21.1**、NeoForge **21.1.252**、Exposure **1.9.19** 的当前源码。安装、启动和兼容概览见 [README](../README.md)。

## 1. 接入边界

公共 Java API 都在 `com.xfw.shuttershadow.api`，随发行 JAR 提供：

| 类 | 用途 | 是否改变世界状态 |
| --- | --- | --- |
| `SeamlessTeleportation` | 玩家和实体的即时无缝传送 | 是 |
| `ChunkLoader` | 描述额外加载的正方形区块区域 | 构造不加载；就绪检查只读取 |
| `ChunkLoading` | 注册和释放服务端保活或玩家额外区块订阅 | 是 |
| `DimensionFilters` | 创建滤镜、读取目标组件、按维度类型换算坐标 | 创建修改新物品栈；解析和换算不传送、不加载区块 |
| `CameraDimensionTeleportEvent` | 通知相机拍摄已成功让玩家换维 | 事件本身只通知；监听者可执行后续行为 |

接入项目需要把 Shuttershadow JAR 放入编译依赖；实际运行还需要 Shuttershadow 及 Exposure。无缝玩家跨维和向玩家同步异维度区块要求**客户端与服务端都安装**本模组及必要依赖。Sodium、Iris 仍是可选客户端模组，不是这些 API 的前置。

`camera`、`item`、`command`、`data`、`loot`、`core`、`client`、`network`、`compat`、`mixin`、`access` 和 `util` 是内部实现，公开可见不等于稳定扩展接口。外部模组优先使用上述 API 和事件，不直接操作相机事务、远程世界注册表或内部传送管理器。本项目当前没有单独发布 API Maven 制品。

## 2. 无缝传送：SeamlessTeleportation

```java
public static @Nullable Entity teleportEntity(
        Entity entity, ServerLevel targetLevel, Vec3 targetPosition);

public static @Nullable ServerPlayer teleportPlayer(
        ServerPlayer player, ServerLevel targetLevel, Vec3 targetPosition);
```

### 参数、线程和结果

`targetPosition` 是实体的**脚底绝对坐标**，不是眼睛坐标，也不自动按下界倍率换算。调用必须位于目标世界所属的服务器线程。网络线程、异步任务或其他服务器线程的请求应先交给 `server.execute(...)`。

入口拒绝下列输入并返回 `null`：任一参数为空；调用线程不是所属服务器线程；实体和目标世界不属于同一服务器；实体已死亡或移除；目标 `ServerLevel` 不是该服务器实际注册的对象；坐标包含 NaN 或无穷；玩家正在相机传送保护期内且没有处于胶卷内部的明确传送作用域。

成功后入口还检查返回实体活着、没有被移除、已位于目标世界、脚底位置与请求位置误差的平方不超过 `1.0E-8`。检查失败返回 `null`。内部运行时异常会继续抛给调用方；`null` 表示未确认成功，并不承诺能回滚已发生的内部副作用。

| 方法 | 返回和身份契约 |
| --- | --- |
| `teleportEntity(...)` | 成功返回实际目标实体。同维度保留原对象；普通实体跨维会重建，必须使用返回引用。 |
| `teleportPlayer(...)` | 成功返回原 `ServerPlayer` 实例；失败或拒绝返回 `null`。 |

### 现有行为

传送入口立即提交移动，**不会等待目的地 3×3 区块或完整视距，不会扫描虚空后再决定是否传送**。无缝指客户端继续使用多维度世界上下文并接管目标世界，不能保证一个从未生成的落点已显示全部地形。

该入口沿用强制传送行为，**不触发 NeoForge 可取消的维度旅行事件**。它不自动选择安全落点、不避开方块、不检查世界边界、不弹出同意界面，也不拍照。玩家维度胶卷自己的名单、保护和客户端清理由相机业务层负责；外部 API 不公开绕过保护的作用域。

玩家正在乘坐的**直接载具**，例如船或矿车，会随玩家跨维并恢复乘坐。其他乘客留在原维度；不会递归搬运整棵乘客树。其他原维度观察者会清除旧载具，当前传送玩家保留客户端载具用于无缝交接。普通实体跨维传送会解除骑乘关系，同维传送不主动解除；它们没有玩家的自动携带规则。不要把已跨维重建的旧实体引用继续用于后续操作。

### 可直接使用的示例

```java
import com.xfw.shuttershadow.api.SeamlessTeleportation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class TeleportExample {
    public static void moveToNether(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.execute(() -> {
            ServerLevel target = server.getLevel(Level.NETHER);
            if (target == null) return;
            ServerPlayer moved = SeamlessTeleportation.teleportPlayer(
                    player, target, new Vec3(20.5, 80.0, 20.5));
            if (moved != null) {
                moved.setDeltaMovement(Vec3.ZERO);
            }
        });
    }
}
```

普通实体示例应在服务器线程执行：

```java
Entity moved = SeamlessTeleportation.teleportEntity(entity, target, destination);
if (moved != null) {
    entity = moved;
}
```

### /tps 指令

```mcfunction
/tps <目标实体> <目标维度> <x> <y> <z>
/tps @s minecraft:the_nether 0 80 0
/tps @e[type=minecraft:pig,distance=..8] minecraft:overworld 0 80 0
/execute in minecraft:the_end run tps @s minecraft:overworld ~ ~ ~
```

权限等级 **2**。实体参数支持选择器，维度由原版 `DimensionArgument` 解析，坐标由原版 `Vec3Argument` 解析，支持 `~` 和 `^`。坐标以**命令源**解析，不以每个目标实体分别解析，不进行维度倍率转换；整数 X/Z 的默认居中规则也沿用原版参数。命令在移动任何实体前使用原版可生成坐标边界检查。

每个目标都调用 `SeamlessTeleportation.teleportEntity`。指令返回实际成功数，部分失败时同时报告成功与拒绝数量，仍服从相机保护。批量选择玩家与其载具时，命令会查找先前由玩家携带到目标世界的载具新实例，避免操作已被替换的旧副本。`tps` 是传送指令，不是服务器每秒 tick 数查询。

## 3. 区块区域：ChunkLoader

```java
public record ChunkLoader(ResourceKey<Level> dimension, int x, int z, int radius)
```

中心 `x/z` 和 `radius` 都以**区块**为单位。方块转区块可使用 `blockX >> 4`、`blockZ >> 4`，负坐标同样有效。范围包含边界，半径 `r` 的窗口为 `(2r+1)×(2r+1)`：

| 半径 | 覆盖区块 |
| --- | --- |
| 0 | 中心 1 个 |
| 1 | 3×3，最多 9 个 |
| 3 | 7×7，最多 49 个 |
| 8 | 17×17，最多 289 个 |

API 自身允许半径 0；相机配置的 3～32 是业务上限范围，不是此对象的构造限制。

| 成员 | 作用和契约 |
| --- | --- |
| `new ChunkLoader(dimension, x, z, radius)` | 只保存不可变区域，不触发区块加载。空维度抛 `NullPointerException`；负半径、`Integer.MAX_VALUE` 半径或边界溢出 int 坐标抛 `IllegalArgumentException`。 |
| `dimension()`、`x()`、`z()`、`radius()` | record 自动生成的字段读取器。 |
| `isFullyLoaded(MinecraftServer server)` | 必须在所属服务器线程调用。检查每个区块已有 `ChunkHolder.getTickingChunk()`，且 `ServerLevel.areEntitiesLoaded(...)` 为真；维度不存在返回 `false`。不主动加载、不阻塞，不单独验证实体 ticking future，也不检查客户端收包、地形编译、光照或截图是否完成。 |
| `foreachChunkPos(consumer)` | 按 X 偏移外层、Z 偏移内层的顺序枚举包含边界的区域，传入维度、区块 X/Z、与中心的切比雪夫距离 `max(abs(dx), abs(dz))`；空 consumer 抛 `NullPointerException`。 |
| `toString()` | 输出 `(维度ID x z radius)`，便于日志记录。 |
| `equals(...)`、`hashCode()` | record 按值比较，可用于区域比较；不能用同值新对象替代注册时的对象进行释放。 |
| `ChunkPosConsumer.consume(dimension, x, z, distanceToSource)` | 枚举回调；距离单位为区块，中心为 0。 |

纯构造、字段读取和区域枚举不访问游戏世界；区域很大时枚举仍会占用调用线程时间。

## 4. 额外区块订阅：ChunkLoading

### 四个公共方法

```java
public static void addGlobalChunkLoader(MinecraftServer server, ChunkLoader loader);
public static void removeGlobalChunkLoader(MinecraftServer server, ChunkLoader loader);
public static void addChunkLoaderForPlayer(ServerPlayer player, ChunkLoader loader);
public static void removeChunkLoaderForPlayer(ServerPlayer player, ChunkLoader loader);
```

| 方法 | 加载与同步行为 |
| --- | --- |
| `addGlobalChunkLoader(MinecraftServer server, ChunkLoader loader)` | 注册服务端区域保活，**不额外同步给客户端**。相机生物查询或其他服务端任务可使用。 |
| `removeGlobalChunkLoader(MinecraftServer server, ChunkLoader loader)` | 按加载对象身份释放全局请求。 |
| `addChunkLoaderForPlayer(ServerPlayer player, ChunkLoader loader)` | 注册区域加载，同时向指定玩家同步区块与允许其看到的实体；两端需安装本模组。 |
| `removeChunkLoaderForPlayer(ServerPlayer player, ChunkLoader loader)` | 按原玩家实例和原加载对象身份释放玩家订阅。 |

所有方法都必须在所属服务器线程执行；错误线程抛 `IllegalStateException`，空参数抛 `NullPointerException`。注册时维度必须实际存在，否则抛 `IllegalArgumentException`；玩家注册还要求传入该服务器玩家列表中当前有效且未移除的 `ServerPlayer`，否则抛 `IllegalArgumentException`。

注册只安排调度，**不是立即完成加载或渲染的保证**。同一世界的重复区域会与其他订阅合并；玩家当前原维度已经由原版同步的区块不会再按相机订阅重复发送。全局订阅没有客户端画面，玩家订阅也不自行把相机切到另一个世界。

### 释放与所有权

调用方必须保留注册时的**原 loader 实例**。即使两个 record 值相等，新建的同值 loader 也不能释放原请求。独立用途使用独立对象；同一个对象多次注册的记录会在一次按身份释放时一并去掉。

玩家订阅还必须保留原 `ServerPlayer` 对象。复活产生同 UUID 新对象时，旧订阅仍由旧对象拥有。释放允许目标维度已移除、玩家已退出或已复活；重复释放安全，不会为已清理玩家重建记录。全局释放必须使用原注册服务器。

正常完成、超时、取消、异常、玩家退出及所属任务结束都应释放。释放仅去掉请求，调度器保留其他用途覆盖的订阅；已有票据按正常清理阶段回收，客户端卸载也按订阅调度发生，**不会马上强制卸载区块**。服务端关闭时内核还会整体清理运行时状态，但不能把它作为常驻任务忘记释放的替代。

### 最小订阅示例

```java
import com.xfw.shuttershadow.api.ChunkLoader;
import com.xfw.shuttershadow.api.ChunkLoading;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;

public final class SubscriptionExample {
    public static ChunkLoader open(ServerPlayer player, ServerLevel target, BlockPos center) {
        ChunkLoader loader = new ChunkLoader(
                target.dimension(), center.getX() >> 4, center.getZ() >> 4, 3);
        ChunkLoading.addChunkLoaderForPlayer(player, loader);
        return loader;
    }

    public static void close(ServerPlayer originalPlayer, ChunkLoader originalLoader) {
        ChunkLoading.removeChunkLoaderForPlayer(originalPlayer, originalLoader);
    }
}
```

`open`、`close` 都由所属服务器线程调用，调用者把返回 loader 与原 player 一起保存。若使用全局保活，把两个方法替换成 `addGlobalChunkLoader(server, loader)` / `removeGlobalChunkLoader(server, loader)`。

外部任务确实需要就绪时，可在后续服务器 tick 轮询 `loader.isFullyLoaded(server)`，并设超时和取消处理。不要在服务器线程 `join`、忙循环或睡眠等待；当前玩家胶卷和 `/tps` 不需要加这一步。

API 不会自动读取相机专用 `max_view_distance` 来裁剪外部 loader。它沿用内核加载开关和票据活跃等级：禁用服务端 `core.enableRemoteChunkLoading` 后不添加新的主动加载票据，区域可能一直不就绪；不要把它当成无条件完成的 future。

## 5. 滤镜和坐标：DimensionFilters

### 全部公共方法

```java
public static ItemStack create(ResourceLocation targetDimension);
public static @Nullable ResourceLocation target(ItemStack filter);
public static @Nullable Route resolve(ItemStack filter, ResourceLocation sourceDimension);
public static double horizontalScale(Level source, Level target);
public static Vec3 mapAbsolute(Vec3 position, double scale);
public static Vec3 mapRelative(Vec3 delta, Vec3 targetOrigin, double scale, double yOffset);
```

| 方法 | 输入、输出和边界 |
| --- | --- |
| `create(ResourceLocation targetDimension)` | 创建数量为 1 的 `shuttershadow:dimension_filter`，写入目标组件。空目标抛 `NullPointerException`；不检查目标世界或 Exposure 滤镜条目是否存在。需在物品注册完成后调用。 |
| `target(ItemStack filter)` | 只读取本模组统一维度滤镜的目标组件；null、其他物品、未指定目标返回 `null`。 |
| `resolve(ItemStack filter, ResourceLocation sourceDimension)` | 从物品栈目标组件生成 `Route`；null/空物品、空来源、缺少目标或来源已经等于目标时返回 `null`。不查询 Exposure 注册表，不限制来源白名单，不检查目标世界是否注册；其他携带目标组件的物品也可解析。 |
| `horizontalScale(Level source, Level target)` | 调用 `DimensionType.getTeleportationScale`，返回两个维度类型的原生 X/Z 倍率。 |
| `mapAbsolute(Vec3 position, double scale)` | 返回 `(position.x*scale, position.y, position.z*scale)`，保持脚底 Y。 |
| `mapRelative(Vec3 delta, Vec3 targetOrigin, double scale, double yOffset)` | 返回 `targetOrigin + (delta.x*scale, delta.y+yOffset, delta.z*scale)`，用于固定取景基准后的相对位移。 |

这组方法可在客户端与服务端使用，不会打开相机、发包、加载区块、检查落点或传送实体。使用当前逻辑线程拥有的物品和世界；不要从其他线程同时修改同一个 `ItemStack`。向量换算不另作有限数或边界检查，传送 API 会检查最终坐标是否有限。

### Route record 与坐标比例

```java
public record Route(ResourceLocation filter, ResourceLocation dimension)
```

`filter()` 是物品注册 ID，例如 `shuttershadow:dimension_filter`；`dimension()` 是实际滤镜物品栈中组件指定的目标维度 ID。它不保存 Exposure 数据条目 ID、来源限制或配置倍率。构造时任一字段为空抛 `NullPointerException`，record 提供不可变读取和按值比较。

```text
X/Z 倍率 = 来源维度类型 coordinate_scale ÷ 目标维度类型 coordinate_scale
```

| 来源 | 目标 | 原生 X/Z 倍率 |
| --- | --- | --- |
| 主世界 | 下界 | 1/8 |
| 下界 | 主世界 | 8 |
| 主世界 | 末地 | 1 |
| 下界 | 末地 | 8 |
| 末地 | 下界 | 1/8 |

Y 保持不变。自定义维度使用各自维度类型的 `coordinate_scale`。滤镜 JSON 不再提供 `routes`、`source_dimension` 或 `coordinate_scale`，也没有本地 JSON 路由配置。

### 从滤镜组件传送的完整例子

```java
import com.xfw.shuttershadow.api.DimensionFilters;
import com.xfw.shuttershadow.api.SeamlessTeleportation;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class FilterTeleportExample {
    public static boolean transfer(ServerPlayer player) {
        ServerLevel source = player.serverLevel();
        ItemStack filter = DimensionFilters.create(ResourceLocation.parse("minecraft:the_nether"));
        DimensionFilters.Route route = DimensionFilters.resolve(filter, source.dimension().location());
        if (route == null) return false;
        ServerLevel target = source.getServer().getLevel(
                ResourceKey.create(Registries.DIMENSION, route.dimension()));
        if (target == null) return false;
        double scale = DimensionFilters.horizontalScale(source, target);
        Vec3 position = DimensionFilters.mapAbsolute(player.position(), scale);
        return SeamlessTeleportation.teleportPlayer(player, target, position) != null;
    }
}
```

在服务器线程执行 `transfer`。例子只演示解析和移动，不模拟拍照，也不会自动发布 `CameraDimensionTeleportEvent` 或应用相机安全附魔。

## 6. 数据包维度滤镜

### 文件位置和 ID

```text
data/shuttershadow/dimension_filter/<目标维度命名空间>/<目标维度路径>.json
assets/shuttershadow/models/item/dimension_filter/<目标维度命名空间>/<目标维度路径>.json
```

内置目标是 `minecraft:overworld`、`minecraft:the_nether`、`minecraft:the_end`。自定义目标 `example:moon/inner` 对应：

```text
data/shuttershadow/dimension_filter/example/moon/inner.json
assets/shuttershadow/models/item/dimension_filter/example/moon/inner.json
```

前者属于数据包，后者属于客户端资源包。两者使用相同目标路径，目标世界本身仍须由数据包或其他模组注册。滤镜统一使用物品 ID `shuttershadow:dimension_filter`，组件 `shuttershadow:dimension_filter_target` 储存目标 ID。

### 完整示例

放到 `data/shuttershadow/dimension_filter/example/moon/inner.json`：

```json
{
  "predicate": {
    "items": "shuttershadow:dimension_filter",
    "components": {
      "shuttershadow:dimension_filter_target": "example:moon/inner"
    }
  },
  "attachment_texture": "exposure:textures/gui/filter/stained_glass.png",
  "attachment_tint": "78A7FF",
  "shader": "shuttershadow:shaders/post/neutral.json"
}
```

| 字段 | 当前含义 |
| --- | --- |
| `predicate.items` | Exposure 滤镜物品选择条件，统一维度滤镜为 `shuttershadow:dimension_filter`。 |
| `predicate.components.shuttershadow:dimension_filter_target` | 用目标组件选中对应变体。相机实际观察目标也从物品栈的同名组件读取。 |
| `attachment_texture` | Exposure 相机附件界面中的镜片纹理。 |
| `attachment_tint` | 镜片纹理颜色，示例为不含 `#` 的六位十六进制字符串；不修改手持模型，也不直接把照片染成此色。 |
| `shader` | Exposure 照片后处理资源，与 Iris 光影包无关；本模组提供中性的后处理资源。 |

从任何不同于目标的来源维度都可使用此滤镜。滤镜不再配置来源数组或距离比例，坐标自动按来源和目标维度类型换算。

仅在 JSON 的组件谓词中写目标，不会自动给任意物品栈补组件。用 `DimensionFilters.create(...)`、创造栏变体或原版指令给出带组件的物品，例如：

```mcfunction
/give @s shuttershadow:dimension_filter[shuttershadow:dimension_filter_target="example:moon/inner"]
```

其他模组物品也可携带目标组件，前提是同时匹配有效的 Exposure 滤镜条目并能放入相机滤镜槽。`DimensionFilters.target()` 只识别本模组物品，`resolve()` 则可解析其他携带组件的物品。

### Exposure 注册表、资源覆盖与创造栏

新目录映射到 Exposure 原有的 `exposure:filter` 动态注册表，不增加独立同步协议。月球示例的条目 ID 是 `shuttershadow:example/moon/inner`，末地内置条目 ID 是 `shuttershadow:minecraft/the_end`。

标准 `data/<namespace>/exposure/filter/*.json` 仍由 Exposure 加载。标准目录与本模组目录产生相同条目 ID 时，数据包堆叠优先级较高者胜出；同包同优先级时，本模组目录优先。客户端 known-pack 本地读取也使用该目录映射。

Exposure 外层物品谓词仍控制滤镜是否可用及附件显示；Shuttershadow 的目标组件解析不另查询该注册表。服务端观察请求还会核对实际活动相机、附件物品 ID、目标组件、支架控制者和会话身份，避免迟到请求把目标切成旧滤镜的维度。

创造栏从 Exposure 滤镜条目中枚举变体：`items` 集合需实际包含本模组统一滤镜，组件谓词明确给出目标组件值；按目标去重。只给范围更宽的组件子谓词而没有具体目标值时，无法据此生成各目标变体。

旧路由子谓词及旧格式不支持，旧本地路由文件不读取。动态注册表条目修改后重新进入世界；专用服务器重新启动。新增模型和纹理须让客户端取得对应资源包。

## 7. 配置文件与作用域

使用 NeoForge 原生模组配置界面，不需要 Cloth Config。

| 文件 | 作用域与读取方式 |
| --- | --- |
| `config/shuttershadow-server.toml` | `SERVER`：服务器权威，连接时由 NeoForge 同步。NeoForge 21.1.252 默认从实例 `config/` 读取；若世界的 `serverconfig/shuttershadow-server.toml` 已存在，优先使用世界文件。 |
| `config/shuttershadow-client.toml` | `CLIENT`：本机渲染和个人偏好，不由服务器统一覆盖。支架传送同意偏好另由本模组同步给服务器。 |
| `config/shuttershadow-core.toml` | `COMMON`：只保留日志总开关，各物理端独立加载，不同步；单人主机同一进程共用该日志设置。 |

当前已有 TOML 数值不会因为默认值变更自动重置。旧内核 JSON 与 `shuttershadow_dimensions.json` 不读取、不迁移。

### SERVER：玩法与远区块加载

| 完整键 | 默认与范围 | 作用 |
| --- | --- | --- |
| `dimension_camera.max_view_distance` | **8**；**3～32 区块半径** | 限制手持和手动支架目标维度订阅与绘制，实际还受客户端请求及服务器视距限制。不改变普通视频视距、原版模拟距离、生物查询范围或传送时机。 |
| `camera_stand.stand_player_radius` | 8；1～64 方块 | 搜索源维度支架附近可能出镜的玩家，再按视锥、焦距、遮挡和个人同意筛选。手动和红石支架共用。 |
| `mob_dimension_film.capture_radius` | **16**；**1～32 方块** | 以目标相机所在方块的搜索盒沿 X/Y/Z 各方向扩展，再筛选镜头内生物；手持、手动支架、红石支架共用。 |
| `core.serverSideNormalChunkLoading` | `true` | 额外票据要求实体/方块 tick；关时只要求方块 tick，生物可能停止更新，不会取消票据或订阅。更改前退出世界，再重新进入。 |
| `core.enableRemoteChunkLoading` | `true` | 是否添加额外主动加载票据；关时只使用其他原因已加载的区块，观察可能缺地形，生物扫描可能无法就绪。原版玩家区块加载保留。 |
| `core.delayUnloadGenerations` | **4**；1～120 更新代数 | 停止观察后保留区块订阅的代数；界面用红字标为不建议修改。每代 13 游戏刻，超过设定代数才清理。额外加载超过 1200/2000 区块时延迟最多为 2/1 代；重生、断线等强制清理不受此项影响。 |

服务端通过 `RemoteSceneStartS2C.maxRenderDistance` 告知绘制上限，同时限制实际区块订阅；活动会话在服务端 tick 中刷新配置。订阅距离先将玩家请求限制到服务器视距，再取相机上限；客户端绘制取本机视频视距与服务端上限的较小值。性能调整还可进一步缩小原版地形绘制距离。

`max_view_distance` 是正方形订阅窗口半径，3 最多为 7×7 区块，8 最多为 17×17；不是区块总数。配置最小 3 不强制客户端至少加载 3，客户端请求更小仍可更小。它不设置相机专用模拟距离，区块活跃程度由票据等级和服务端状态决定。

生物范围以方块计，不随相机视距缩减，可以跨过中心区块边界；仍须实际存在且通过镜头与遮挡判断。照片使用已经同步的当前可见内容，不等完整画面窗口就绪。玩家传送不等目的地 3×3 区块；生物支架任务仍可能等待获取实体所需的扫描区块，选中后只保活该生物所在区块直到任务完成或取消。

### CLIENT：个人偏好、渲染与警告

| 完整键 | 默认 | 作用 |
| --- | --- | --- |
| `camera_stand.accept_stand_dimension_film_teleport` | `true` | 同意出镜时被支架玩家维度胶卷传送；连接后同步给服务器。拒绝不关闭观察、拍照或投影，不控制 `/tps`。 |
| `core.enableClientPerformanceAdjustment` | `true` | 原版地形流程每 5 秒按近期帧率/可用内存评估缩短相机绘制距离，不改普通视频设置；Sodium 自有地形流程不保证受其控制。 |
| `core.doCheckGlError` | `false` | 额外检查 OpenGL 错误，诊断输出服从日志总开关；只用于排查，不修复错误。关闭日志不会关闭已启用的检查。 |
| `core.saveMemoryInBufferPack` | `false` | 减小本模组远维度中新建的原版区块网格缓冲初始容量，容量不足仍增长。原维度和已有缓冲不受影响，Sodium 自有缓冲不由此控制；修改后建议重启。 |
| `core.enableWarning` | `true` | 统一显示或隐藏全部内核游戏内提醒，包括 Iris 和内存提醒；不影响日志总开关、连接错误或业务检查，不清除已有聊天消息。 |

### COMMON：日志总开关

`shuttershadow-core.toml` 只有顶层键 `enableLogging`，默认 **`false`**。它控制本模组主动输出的控制台及文件日志，包括警告和错误，配置加载前也保持关闭。开启后仍遵守标准日志级别；需要 `DEBUG` 诊断时还须允许该级别。

总开关不控制 Minecraft、NeoForge、Exposure、Sodium、Iris 等外部来源的日志，不改变异常抛出、连接错误或游戏内提醒。服务端管理员可开启服务端本地日志，客户端仍保留自己的设置。

## 8. 相机换维事件、附魔与拍摄边界

### CameraDimensionTeleportEvent

```java
public final class CameraDimensionTeleportEvent extends PlayerEvent {
    public CameraDimensionTeleportEvent(
            ServerPlayer player, ItemStack camera, ResourceKey<Level> sourceDimension);

    public ServerPlayer getEntity();
    public ItemStack getCamera();
    public ResourceKey<Level> getSourceDimension();
    public ResourceKey<Level> getTargetDimension();
}
```

在 **NeoForge 游戏事件总线** `NeoForge.EVENT_BUS` 发布，运行于服务器线程；不可取消。它表示相机已成功让玩家换维，不能撤销该次移动。

| 成员 | 契约 |
| --- | --- |
| `getEntity()` | 已完成换维的 `ServerPlayer`，保留原玩家实例。 |
| `getCamera()` | 拍摄相机快照的副本。构造时复制一次，读取时再返回副本；修改不影响真实相机及其他监听者。 |
| `getSourceDimension()` | 传送前维度。 |
| `getTargetDimension()` | 事件构造时玩家实际所在维度，之后不会随玩家再次移动而改变。 |

手持玩家胶卷自拍、手动支架玩家胶卷、红石支架玩家胶卷通过共同相机传送入口发布。支架每传走一位玩家发布一次，`getCamera()` 均来自该拍摄相机；曝光失效不影响成功换维的通知。

普通拍照、同维传送、拒绝或失败传送不发布；生物胶卷、`/tps` 和直接调用 `SeamlessTeleportation` 也不自动发布。手持自拍先传送再继续拍摄，因此本事件**不表示照片上传或胶卷写入完成**。

事件没有 Exposure 的 `Frame`、照片 ID、`CameraHolder`、出镜列表或拍摄参数，也不区分红石与手动触发。照片信息继续使用 Exposure 自己的帧事件和额外数据接口。外部接入只需监听，不应自行构造并发布它来模拟拍照。

监听示例，使用你自己模组的 mod ID：

```java
import com.xfw.shuttershadow.api.CameraDimensionTeleportEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = "example")
public final class CameraTeleportListener {
    @SubscribeEvent
    public static void onCameraTeleport(CameraDimensionTeleportEvent event) {
        var player = event.getEntity();
        var camera = event.getCamera();
        var source = event.getSourceDimension();
        var target = event.getTargetDimension();
        // 在此处理本模组的相机换维后效果。
    }
}
```

### 附魔注册 ID、等级和获得方式

| 注册 ID / 显示名称 | 等级 | 效果与来源 |
| --- | --- | --- |
| `shuttershadow:exposure_failure` / 曝光失效-相机 | I | 不写胶卷帧、不创建图片、不截图或上传，仍保留快门、Exposure 事件、统计/进度及维度胶卷传送。下界要塞宝箱获得。 |
| `shuttershadow:narcissism` / 自恋狂-相机 | I | 每次打开手持相机默认正面自拍，仍可切回远景；退出恢复原视角。支架无自拍，不改变支架。下界要塞宝箱获得。 |
| `shuttershadow:safe_dimension_teleport` / 安全传送维度-相机 | I～III | 相机成功传送玩家换维后，分别给予 10/20/30 秒缓降及安全传送效果。末地船宝箱获得。 |

三个附魔均为相机专用珍宝，可在铁砧附到 Exposure 相机，可互相叠加，创造栏提供各等级书。前两者是诅咒，砂轮不移除；安全传送是正面附魔。均不加入附魔台、图书管理员交易或普通随机附魔候选标签，数据包可改获得方式。

下界要塞 `minecraft:chests/nether_bridge` 每次生成宝箱战利品有 **30%** 概率追加一本书，两种诅咒等概率。末地船宝箱有 **30%** 概率追加一本安全传送书，I～III 等概率。末地城普通箱不满足船条件；两处均追加而不替换原版宝箱内容。已经开过、已有实际物品的宝箱不会重新生成这些书。

可修改的追加资源：

```text
data/shuttershadow/enchantment/<附魔ID路径>.json
data/shuttershadow/loot_table/chests/nether_camera_books.json
data/shuttershadow/loot_table/chests/end_camera_books.json
data/shuttershadow/loot_modifiers/nether_camera_books.json
data/shuttershadow/loot_modifiers/end_camera_books.json
data/neoforge/loot_modifiers/global_loot_modifiers.json
```

末地船条件 ID 为 `shuttershadow:end_ship_chest`，无附加字段，需战利品上下文包含宝箱位置 `ORIGIN`；按所在末地城的船结构段判定，不把所有 `end_city_treasure` 箱都算成船箱。

### 安全传送效果

效果注册 ID 为 `shuttershadow:safe_dimension_teleport`，属于正面药水效果。相机事件监听者读取相机附魔等级，同时给予原版缓降和新效果，持续 tick 数为 `min(等级, 3) × 200`；两种效果 amplifier 都为 0，原版效果合并保留已有更强或更长效果。

新效果只在拥有它的 `ServerPlayer` 上处理 `LivingIncomingDamageEvent`。当伤害来源 `getEntity()` 和 `getDirectEntity()` **都为 null**，且不是 `minecraft:generic_kill` 时取消伤害。因此火、岩浆、窒息、虚空等无实体来源伤害可免疫，实体攻击、投射物、掉落铁砧等仍会伤害玩家，`/kill` 保留。

它不改变目标落点，不熄火，不把玩家从虚空救回；效果结束后伤害恢复。同维传送、普通拍照及通用传送 API 不自动给予保护。可以通过原版 `/effect` 显式给予新效果；注册效果不代表增加了独立药水瓶或酿造配方。

### Exposure 原事件与胶卷

曝光失效继续执行 Exposure 的 `addNewFrame/onFrameAdded` 事件路径，但没有实际图片和新增胶卷帧。其他模组监听 Exposure 帧事件时不能假定一定存在图像。无胶卷、满卷等原生拍摄资格检查保留；曝光失效不消耗帧数，手持、手动支架、红石支架都生效。

曝光失效通过拍照局部注入跳过写卷、图片和上传，适用于普通拍照及维度胶卷拍摄。Exposure 原事件负责照片语义，`CameraDimensionTeleportEvent` 负责已完成的玩家换维通知。

| 胶卷/入口 | 当前边界 |
| --- | --- |
| 玩家维度胶卷 | 手持物理自拍先传送再自拍；支架从同一拍摄名单传送玩家，个人同意和传送保护保留。 |
| 生物维度胶卷 | 从目标维度选取镜头内符合条件的首个非玩家生物，图片验收后传到相机原维度；曝光失效时无需图片也可完成。普通实体跨维返回新实例。 |
| 红石维度支架 | 拍原维度、不显示玩家，不渲染或等待目标画面；维度胶卷保留玩家/生物传送。曝光失效保留传送且不成片。 |

## 9. 客户端显示与可选渲染兼容

### 相机附件提示框

通过 NeoForge `RenderTooltipEvent.GatherComponents` 与原生 `TooltipComponent`/`ClientTooltipComponent` 显示附件槽位。顺序和数量来自 `CameraItem.getAttachments()`，普通相机依次为胶卷、闪光灯、镜头、滤镜。空槽只显示无边框、无占位图案的深色形状；已装附件显示原物品图标及原生数量、耐久或胶卷进度。

这是只读物品提示框，不提供拖放或额外背包界面。尊重 `HIDE_TOOLTIP`/`HIDE_ADDITIONAL_TOOLTIP`，右键仍使用 Exposure 原界面。显示思路参考 Tide，不需要安装 Tide，没有为此新增 Mixin。

### 原版雾和光影边界

相机远景期间，`MixinGameRenderer` 以当前相机半径乘 16 替换原版 `getRenderDistance()`，`MixinFogRenderer` 让雾颜色混合也采用该视距，并按维度保存和恢复雾状态。源维度的首领雾只在远景绘制期间被隔离，正常画面沿用原版。

光影包可自行计算雾，不一定使用这些原版参数；相机范围较小而游戏视频视距较大时，仍可能显示明显地形边缘。当前不修改 Iris 的 `far` uniform，也没有针对某个光影包新增雾算法。

### 可选模组契约

| 模组 | 当前开发版本/条件 | 接入边界 |
| --- | --- | --- |
| Sodium | 0.8.13，NeoForge 1.21.1；metadata 要求安装时为 0.8.13 或以上 | 可选客户端渲染上下文适配，发行包不内嵌、不要求安装。 |
| Iris | 1.8.14 Beta 1，NeoForge 1.21.1；metadata 要求安装时为此版本或以上 | 可选客户端兼容；保留远景管线及截图颜色/深度附件恢复，手持和手动支架沿用玩家光影设置。具体光影包效果需实测。 |
| Create、Sable | Create 6.0.10、Sable 2.0.6，MC 1.21.1 | 开发兼容环境中的模组，不是 API 或发行模组的必需依赖。 |
| Immersive Portals | 不允许同时安装 | 已整合所需维度内核，metadata 声明与 `immersive_portals_core` 不兼容。 |

兼容 Mixin 按相关模组是否存在启用；无 Sodium/Iris 时使用原版路径。Iris 生成的着色程序以独立名称隔离缓存，普通程序继续原生缓存复用。渲染状态、粒子/声音隔离、异维度世界释放和移动包维度标记仍是内核职责，外部 API 不应手动复制这些流程。

## 10. 网络协议和版本要求

当前 NeoForge payload 协议标识为 **`14`**（`ShuttershadowNetwork.PROTOCOL_VERSION`），相机与内核消息共用，消息 ID 使用 `shuttershadow:*`。客户端与服务端需安装兼容发行包且必需通道、协议标识匹配，由 NeoForge 协商判断兼容；没有额外内核版本比较、容忍标记或拒绝开关。

配置阶段用空就绪请求/回执记录真实连接，保证初始位置包解码时可判断扩展协议能力；此时玩家对象可能尚未创建。该步骤不携带版本或配置数据。登录维度同步继续保留，登出清理连接就绪状态。

原维度主要由原版区块/实体流程同步，额外世界包用维度标记重定向，原版与相机区块批次使用独立回执。观察会话使用递增序号和当前实际滤镜目标校验，迟到旧场景不能替换新目标；照片使用独立负序号。数据包滤镜注册表沿用 Exposure 原生同步。

远实体同步只刷新有额外订阅或待清理观察者的世界，关闭订阅后仍完成解除配对。无缝玩家传送、载具同步与服务端停机释放继续保留；最后伤害者和攻击目标不由本模组额外清理，不再使用全局生物 tick 战斗引用 Mixin。

网络消息、维度数字 ID 和客户端多世界切换属于内核实现，扩展不应直接拼包替代公共 API。修改包字段、Codec 或双方世界语义时需更新两端并维护协议标识。

## 11. 维护验证

修改 API 契约时同步检查本文、实际公开签名与调用者；重点验证服务器线程、玩家/实体身份、返回值、事件触发时机及加载器释放。GPU 光影、多人生物/载具及真实世界生成效果仍需游戏验证。

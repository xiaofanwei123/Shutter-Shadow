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
| `CameraCaptureContext` | 一次拍摄的固定身份、执行者和来源快照 | 读取快照；持有者及玩家仍是真实对象 |
| `CameraCapturePlan` | 拍摄前调整照片、玩家传送和生物传送计划 | 本身不执行动作；受理后冻结并由内部流程执行 |
| `api.event` 中的六类事件 | 控制观察、拍摄、对象选择、帧数据、图片和主体传送 | 按各事件契约调整当前流程或接收通知 |
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

现在采用**单主体传送**：只移动参数指定的玩家或实体，不携带船、矿车或其他乘客。同维和跨维都先让主体下车，并让主体的乘客下车；载具与其他乘客留在来源世界。若其他模组取消下车，使任一骑乘关系没有解除，则返回 `null`，不继续移动。来源和目标已经是同一位置也会执行这项关系清理。不要把跨维重建后已移除的旧实体引用继续用于后续操作。

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

每个目标都独立调用 `SeamlessTeleportation.teleportEntity`，先解除骑乘，仅移动选择器选中的主体，不携带原载具或其他乘客，也不恢复乘坐关系。指令返回实际成功数，部分失败时同时报告成功与拒绝数量，仍服从相机保护。`tps` 是传送指令，不是服务器每秒 tick 数查询。

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

## 8. 相机事件、玩法控制与附魔

### 注册方式与职责边界

六类新事件在 `com.xfw.shuttershadow.api.event`，全部使用 **NeoForge 游戏事件总线** `NeoForge.EVENT_BUS`，不是模组注册总线。监听具体事件，例如 `CameraCaptureEvent.Before`，不要把抽象父事件当作统一监听入口。

事件开放可控制的阶段；内部协调器、自拍事务、支架事务和生物上传事务仍负责调用顺序、网络确认及资源释放。默认玩法不是全部改成事件监听。现有附魔和来源元数据会使用部分新事件，其他扩展也可以在同一边界调整计划。

| 事件类及具体类型 | 发布端与线程 | 可控制内容 |
| --- | --- | --- |
| `CameraViewEvent.Open`、`Tick`、`Close` | 客户端主线程；服务端远场会话在服务器线程另行发布 | 打开/观察时客户端改模式、服务端改观察场景；Tick 可请求关闭；Close 只通知 |
| `CameraCaptureEvent.Before`、`Completed` | 服务器线程 | Before 可取消整次拍摄、修改计划；Completed 报告终态 |
| `CameraSubjectsEvent` | 服务器线程 | 分别筛选照片主体、玩家传送主体、生物传送主体 |
| `CameraFrameEvent` | 服务器线程 | 生成整帧后修改元数据或替换同一图片编号的帧 |
| `CameraImageEvent.Ready` | 客户端主线程 | 在 Exposure 颜色处理前读取或替换图片 |
| `CameraTransferEvent.Before`、`After` | 服务器线程 | Before 可取消单个主体或改目标与朝向；After 报告该主体结果 |

只有 `CameraCaptureEvent.Before` 与 `CameraTransferEvent.Before` 实现 `ICancellableEvent`，可用 `setCanceled(true)`。观察关闭用 `CameraViewEvent.Tick.requestClose()`；帧和图片不提供取消接口。需要取消成片而保留传送，应在拍摄 Before 设置 `NO_IMAGE`，不要取消整次拍摄。

`@EventBusSubscriber(modid = "example")` 默认注册到游戏事件总线。使用自己的 mod ID；涉及 `CameraImageEvent` 或 Minecraft 客户端类的监听器须单独限制为 `value = Dist.CLIENT`。观察事件即便写在客户端专属监听类，单人游戏仍可能收到集成服务端发布的事件，所以还要检查 `getContext().side()`。

### CameraCaptureContext：固定拍摄身份

上下文由真实拍摄入口建立，异步恢复不重新建立身份、不重复发布拍摄 Before。扩展应读取事件提供的上下文，不自行构造上下文并发布事件来启动拍摄。

| 方法 | 当前契约 |
| --- | --- |
| `getShotId()` | 本次逻辑拍摄的 UUID；同一次延迟截图、上传和传送共享该编号 |
| `getTrigger()` | `HANDHELD`、`MANUAL_STAND`、`REDSTONE` |
| `getHolder()` | 真实来源 `CameraHolder`；支架返回支架持有者，不是代拍玩家 |
| `getExecutor()` | 首次受理时负责截图、上传的固定 `ServerPlayer` 实例；红石时不代表实际按按钮的人 |
| `getCamera()` | 首次受理时的相机快照，每次读取返回独立副本，修改不会写回实际相机 |
| `getSourceLevel()`、`getSourcePosition()` | 拍摄开始时的来源世界及持有者脚底位置，不随自拍换维变化 |
| `isSelfie()` | 开始时是否为手持自拍；支架为 `false` |
| `getObservationDimension()` | 开始时可解析的观察目标；无远场时为 `null`，不是最终照片世界或玩家目的地 |

`getHolder()` 和 `getExecutor()` 保留真实对象引用，实体可能在后续阶段移动、死亡或换维。需要原始位置时使用来源快照，不重新从真实实体读取。同一支架被其他玩家接手后，已受理拍摄的上传及生物事务仍属于原执行者。重新登录或复活后的同 UUID 新玩家对象不能冒领旧回执。

观察 `sessionId`、服务端拍摄 `shotId` 和 Exposure `exposureId` 是三个不同身份。`CameraFrameEvent` 的 Frame 带图片编号；客户端图片事件通过 `getExposureId()`、`getCameraId()`、来源维度和持有者 ID 识别截图，不直接提供服务端 `CameraCaptureContext`。

### CameraCapturePlan：只在拍摄 Before 修改

`CameraCaptureEvent.Before.getPlan()` 返回共享计划，监听者按总线优先级依次修改。事件派发结束后计划冻结；之后调用 setter 会抛 `IllegalStateException`。缺失的照片世界，或已启用传送所需的查询/目标世界不存在，会终结为 `FAILED`。保存计划引用不等于可以在上传时再改目的地。

| setter | 控制内容与边界 |
| --- | --- |
| `setPhotoOutput(PhotoOutput output)` | `PHOTO` 正常成片；`NO_IMAGE` 不写卷、不生成图片、不要求上传，但继续拍摄逻辑和已启用传送 |
| `setPhotoScene(ResourceLocation dimension, @Nullable Vec3 position)` | 指定照片世界及镜头脚底坐标；空坐标沿用观察锚点或原生维度换算。不修改玩家目的地 |
| `setFilterShader(@Nullable ResourceLocation shader)` | 指定 Exposure 照片后处理资源；`null` 明确取消该照片滤镜，不关闭 Iris 光影 |
| `setPlayerTransfer(boolean enabled)` | 启用/停用玩家传送；手持仍须自拍，支架仍筛选有效出镜玩家 |
| `setPlayerDestination(ResourceLocation dimension, @Nullable Vec3 position)` | 玩家目标世界及固定脚底坐标；空坐标为各玩家沿当前锚点/原生比例映射 |
| `setMobTransfer(boolean enabled)` | 启用/停用生物传送；不把照片名单当作生物名单 |
| `setMobQueryDimension(ResourceLocation dimension)` | 查询生物的世界，独立于照片世界 |
| `setMobCaptureRadius(int radius)` | 本次生物查询范围，1～32 方块；不修改服务器配置 |
| `setMobDestination(ResourceLocation dimension, @Nullable Vec3 position)` | 生物目标世界及固定脚底坐标；空坐标回来源镜头映射，另指定世界时按原生比例换算 |

计划通过 `getPhotoOutput()`、`getPhotoDimension()`、`getPhotoPosition()`、`isPhotoSceneChanged()`、`isShaderChanged()`、`getFilterShader()`、`isPlayerTransfer()`、`getPlayerDimension()`、`getPlayerPosition()`、`isMobTransfer()`、`getMobQueryDimension()`、`getMobCaptureRadius()`、`getMobDimension()`、`getMobPosition()` 与 `isFrozen()` 读取当前状态。`freeze()` 由协调器调用；监听者不要提前冻结其他监听者仍需修改的计划。

所有 setter 要求相应非空字段有效；坐标 setter 拒绝 NaN/无穷，查询半径超范围抛 `IllegalArgumentException`。开启传送不会自动补齐目标：没有滤镜路由的普通拍摄若要新增玩家传送，须同时设置玩家目的地；新增生物传送须设置查询世界和目标世界。原生无胶卷、满卷等拍摄资格检查仍保留，计划不提供绕过它们的入口。

曝光失效监听以 `HIGHEST` 优先级把输出设为 `NO_IMAGE`，后续较低优先级监听可明确改回 `PHOTO`。自恋狂也通过客户端 Open 的 `HIGHEST` 监听给出默认自拍，后续监听可改回 `NORMAL`。同优先级不应依赖注册顺序。

### CameraViewEvent：打开、观察与关闭

客户端在真实取景器建立时发布一次 Open，正常有效游戏刻发布 Tick，退出时发布 Close；暂停游戏不继续发布有效观察 Tick。它覆盖普通相机、维度手持相机和手动支架。服务端只针对通过校验的远场观察会话发布相应事件，不为普通相机或红石后台截图虚构一次交互观察。

`getContext()` 返回 `CameraViewEvent.Context`。record 的读取器是 `sessionId()`、`side()`、`player()`、`camera()`、`cameraStandId()`、`sourceDimension()`、`sourcePosition()`、`targetDimension()`、`targetPosition()`、`mode()`，另有 `supportsSelfie()`。相机栈是隔离副本，真实玩家引用不复制。手持 `cameraStandId() < 0`，支架使用真实实体 ID；普通观察或客户端尚未收到场景确认时目标可以为空。两端会话编号独立，不能把客户端编号直接当成服务端编号使用。

| 方法 | 权限与行为 |
| --- | --- |
| `Open/Tick.trySetMode(Mode mode)` | 只允许 `Side.CLIENT`；`NORMAL` 为远景、`SELFIE` 为自拍，支架拒绝自拍。成功返回 `true`，跨端/不支持返回 `false` |
| `Open/Tick.trySetScene(ResourceLocation dimension, @Nullable Vec3 position)` | 只允许 `Side.SERVER`；空位置按来源锚点与原生比例换算，明确位置必须有限且 X/Z 不超过 ±30,000,000。返回 `true` 不代表目标世界已经通过后续校验或加载完成 |
| `Tick.requestClose()`、`isCloseRequested()` | 请求走正常清理流程关闭当前观察；不是取消拍摄，不能当作红石拍摄开关 |
| `Close.getReason()` | `NORMAL`、`CAMERA_CHANGED`、`DIMENSION_CHANGED`、`DEATH`、`DISCONNECTED`、`INVALIDATED`、`SERVER_STOPPING`、`EVENT_REQUESTED` |

监听者修改后再次调用 `getContext()` 可读取目前共享的模式/场景。服务端会验证目标世界存在且不是当前来源世界，再更新实际订阅和发包；不会让客户端 `trySetScene` 获得权威。修改观察场景会影响后续拍摄默认观察锚点，但不等于当前拍摄 Before 的照片或传送计划已经改变。

Close 不可取消。服务端单会话先移除索引、释放订阅，再通知监听者；客户端退出也保证原生取景器清理继续执行。登出和停服时，即便某个 Close 监听抛异常，也会尽量清理其他会话及后台截图请求，收集异常后继续抛出；不是静默吞掉错误。监听器应自行处理其可恢复异常，不把复杂阻塞任务放在 Tick 中。

### CameraSubjectsEvent：三个独立名单

通过 `getContext()`、`getPurpose()`、`getCandidates()`、`getSubjects()` 读取当前阶段。候选和返回名单不可修改，使用 setter 提交新名单。

| `Purpose` | 默认选择与修改方式 |
| --- | --- |
| `PHOTO` | 当前照片查询到的实体；手动远景支架还可能有来源玩家的投影。红石维度支架默认源照片移除玩家。使用 `getPhotoSubjects()` / `setPhotoSubjects(...)` |
| `PLAYER_TRANSFER` | 手持自拍为本人；支架为源维度经投影和镜头筛选的玩家。使用 `getTransferSubjects()` / `setTransferSubjects(...)` |
| `MOB_TRANSFER` | 查询世界中符合范围与镜头条件的非玩家生物，默认首个；可选多个。使用 `getTransferSubjects()` / `setTransferSubjects(...)` |

调用错误阶段的 setter 会抛 `IllegalStateException`。setter 去重且只保留已有候选，协调器还会再次去掉死亡或移除实体。支架玩家传送仍检查在线实例、来源世界、个人同意和传送保护。选空名单可只取消该类主体；不能通过名单添加范围外实体或绕过个人同意。

照片主体控制实体元数据及原生照片相关行为，**不删除截图中的像素**。默认红石源照片不显示玩家的视觉效果由截图场景负责，不能仅靠移除照片元数据名单来隐藏图像中的玩家。照片和传送候选若能复用查询仍分别发布事件，筛选一个阶段不会覆盖另一个。

### CameraFrameEvent：服务端整帧数据

在 Frame 数据生成完毕、实际写入胶卷之前发布，`NO_IMAGE` 也发布。`getContext()` 返回拍摄身份，`getFrame()` 返回当前不可变 Frame，`getExtraData()` 返回本事件可修改的帧级 `ExtraData`。`replaceFrame(Frame replacement)` 可替换整帧，但图片编号必须与原 Frame 相同，否则抛 `IllegalArgumentException`；不要改编号来绑定另一次上传。

实体级元数据可从 `getFrame()` 建立 `Frame.Mutable`，调整实体条目后调用 `replaceFrame(mutable.toImmutable())`。本事件不保证图像已经生成、胶卷已经写入或玩家尚未换维：手持自拍的换维在本阶段之前。

### CameraImageEvent.Ready：客户端图像及所有权

原始照片图片成功生成后、进入 Exposure 的颜色/调色板处理与上传之前，在客户端主线程发布一次 Ready。`NO_IMAGE`、截图失败或未真正生成图片的拍摄没有 Ready。事件不代表服务端已接收图片。

`getExposureId()` 是图片编号，`getCameraId()` 是可能为空的相机稳定 ID，`getSourceDimension()` 和 `getCameraHolderId()` 是创建截图任务时的真实来源身份。远景截图的来源仍为源相机/支架，不能用当前客户端世界推断作者。`getCaptureParameters()` 每次返回参数副本，额外数据也复制；修改它不会改变当前任务。

`getImage()` 返回**借用**的 `Image`，只能在同步监听期间读取或生成独立替换图片。不要关闭、异步持有或在监听结束后继续读原图。`replaceImage(Image replacement)` 接管新图并立即关闭被替换旧图；传入当前同一对象不重复关闭，已替换关闭的图不能再传回。最终图像交给 Exposure 的后续任务管理，监听者不要再释放它。派发异常时当前图像也会清理，异常沿原任务链传播。

### CameraTransferEvent：每个主体的实际移动

相机玩家/生物传送入口分别为每个显式选中的主体发布 Before 与 After；同一张照片选中多个主体会有多对事件。直接无缝 API 与 `/tps` 不发布它。取消 Before 只阻止当前主体，不取消照片、其他主体或之前已经成功的传送。

共同读取方法为 `getContext()`、`getCamera()`、`getEntity()`、`getEntityId()`、`getSourceLevel()`、`getSourcePosition()`、`getTargetLevel()`、`getTargetPosition()`、`getYaw()` 和 `getPitch()`。相机是副本，来源在移动前固定，`getEntityId()` 保留 UUID。`getEntity()` 是原主体，普通实体跨维后可能已移除；所属拍摄上下文在未建立事务的内部入口中可能为 `null`，扩展须检查。

Before 另有 `setTargetLevel(ServerLevel)`、`setTargetPosition(Vec3)`、`setYaw(float)`、`setPitch(float)`。实际执行前检查服务器/世界身份、实体存活、有限坐标与朝向；取消或参数拒绝不会先退出正在操作的支架相机。移动仍服从无缝 API 的保护与单主体规则。

After 通过 `getResult()` 返回 `SUCCESS`、`CANCELED`、`REJECTED` 或 `FAILED`，`isSuccessful()` 简化成功判断。`getMovedEntity()` 只在 API 确认成功时返回实际目标实体；普通实体必须用此新引用。`getFailure()` 为执行时的运行时异常，取消或参数拒绝通常为空。After 不可取消，失败也不保证回滚已经发生的世界变更。

### CameraCaptureEvent.Completed：整次拍摄终态

本事件只发布一次，发布前移除本次索引并释放它拥有的失败/取消资源，迟到回执不能完成旧拍摄两次。它在 `nativeReturned` 与支架动作结束后才可能完成；`PHOTO` 还要求服务端接收该执行者的对应图片。快门声、Frame 生成与 Completed 是不同阶段，不应相互代替。

| `getResult()` | 含义 |
| --- | --- |
| `IMAGE_RECEIVED` | 本次图片已被服务端接收，原生拍摄与支架等待已结束；不保证世界数据已保存到磁盘 |
| `NO_IMAGE` | 免成片流程已结束，没有图片和新增胶卷帧 |
| `CANCELED` | 拍摄 Before 取消，不继续本次成片或传送 |
| `FAILED` | 计划无效、截图/图片处理失败、执行者失效、超时或执行异常等 |

`hasImage()`、`wasFilmWritten()`、`getTransferredEntities()` 与 `getFailureReason()` 分别报告是否收图、是否实际写卷、已确认成功传送主体的不可变 UUID 列表以及取消/失败说明。成功说明为空字符串。`FAILED` 不等于没有写卷或没有传送，尤其手持自拍可能已经移动后才发生图片失败；应分别读取这些字段。

### 五种默认玩法与可控边界

| 默认玩法 | 默认照片与主体时序 | 扩展可以修改什么 |
| --- | --- | --- |
| 手持/手动支架 + 维度滤镜，普通胶卷拍目标世界 | 当前可见目标画面；不等完整视距 | Before 改照片世界、位置、滤镜或免成片；PHOTO 改照片元数据主体；Frame/Image 分别改数据和像素 |
| 手持/手动支架 + 玩家维度胶卷 | 手持自拍先传本人，客户端确认换维后再拍；手持远景不主动传本人。手动支架先截图完成，再传有效出镜玩家 | Before 开关与目的地；PLAYER_TRANSFER 筛候选；Transfer.Before 控制单个主体 |
| 手持/手动支架 + 生物维度胶卷 | 查询目标世界，默认首个非玩家；成片上传成功后传回来源，免成片不等上传 | Before 改查询/目标世界、范围、坐标；MOB_TRANSFER 可选多个候选；Transfer.Before 独立取消或重定向 |
| 红石支架 + 玩家维度胶卷 | 默认拍原维度并隐藏玩家，不渲染目标照片；截图完成后传源镜头筛选的玩家 | Before 可免成片、停传送或明确改照片场景；PLAYER_TRANSFER 与 Transfer 控制传送 |
| 红石支架 + 生物维度胶卷 | 默认拍原维度，目标生物扫描独立进行；上传成功后传生物，免成片直接走传送 | Before 独立设置照片、查询和生物目的地；MOB_TRANSFER 与 Transfer 控制候选和实际移动 |

这五种行为以现有附件和默认计划为起点；扩展可让两个传送开关同时启用，或替普通照片增加目标，但仍受对应入口能力和候选资格约束。无缝传送保留，船/矿车不随玩家传送。红石默认不建立真实观察 Open/Tick；没有维度路由的普通红石相机沿原照片流程，不套用维度支架的隐藏玩家规则。

服务端一般从 Before 进入对象查询、Frame、原生拍摄和支架结束，再进入 Completed；玩家自拍的 Transfer 在 Frame 前，支架玩家的 Transfer 在截图确认后，生物的 Transfer 在上传验收后。`NO_IMAGE` 不创建客户端图片阶段，但保留对象、Frame、传送及原生拍摄后的统计/进度行为。不要假定所有玩法有同一条固定事件顺序，尤其不能把客户端 Ready 当成服务器传送完成通知。

### 可直接注册的服务端监听示例

以下示例让红石拍摄免成片、把生物名单限制为候选中的动物、追加来源元数据，并在整次完成时通知执行者；它不更改默认玩家传送开关。只需把 `example` 改为接入模组 ID，各方法也可独立采用。

```java
import com.xfw.shuttershadow.api.CameraCaptureContext;
import com.xfw.shuttershadow.api.CameraCapturePlan;
import com.xfw.shuttershadow.api.event.CameraCaptureEvent;
import com.xfw.shuttershadow.api.event.CameraFrameEvent;
import com.xfw.shuttershadow.api.event.CameraSubjectsEvent;
import io.github.mortuusars.exposure.util.ExtraData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Animal;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = "example")
public final class CameraGameplayListener {
    private static final ExtraData.Type<ResourceLocation> SOURCE =
            ExtraData.Type.resourceLocation("example_capture_source");

    @SubscribeEvent
    public static void before(CameraCaptureEvent.Before event) {
        if (event.getContext().getTrigger() == CameraCaptureContext.Trigger.REDSTONE) {
            event.getPlan().setPhotoOutput(CameraCapturePlan.PhotoOutput.NO_IMAGE);
        }
    }

    @SubscribeEvent
    public static void subjects(CameraSubjectsEvent event) {
        if (event.getPurpose() == CameraSubjectsEvent.Purpose.MOB_TRANSFER) {
            event.setTransferSubjects(event.getCandidates().stream()
                    .filter(entity -> entity instanceof Animal).toList());
        }
    }

    @SubscribeEvent
    public static void frame(CameraFrameEvent event) {
        event.getExtraData().put(SOURCE,
                event.getContext().getSourceLevel().dimension().location());
    }

    @SubscribeEvent
    public static void completed(CameraCaptureEvent.Completed event) {
        event.getContext().getExecutor().sendSystemMessage(Component.literal(
                "拍摄结果：" + event.getResult()
                + "，成功传送：" + event.getTransferredEntities().size()));
    }
}
```

想把普通手持自拍设为特定目的地，在 Before 中先调用 `setPlayerDestination(ResourceLocation.parse("minecraft:the_end"), new Vec3(0.5, 80, 0.5))`，再调用 `setPlayerTransfer(true)`。仅设置目的地不自动启用传送，手持也仍须处于自拍。全局取消用 `event.setCanceled(true)`，仅取消某人用 `CameraTransferEvent.Before.setCanceled(true)`。

### 可直接注册的客户端监听示例

示例在打开手持相机时设为自拍，并在图片颜色处理前记录尺寸。它只读取借用图像，不提前释放 Exposure 后续还会使用的资源。

```java
import com.xfw.shuttershadow.api.event.CameraImageEvent;
import com.xfw.shuttershadow.api.event.CameraViewEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = "example", value = Dist.CLIENT)
public final class CameraClientListener {
    @SubscribeEvent
    public static void opened(CameraViewEvent.Open event) {
        if (event.getContext().side() == CameraViewEvent.Side.CLIENT
                && event.getContext().supportsSelfie()) {
            event.trySetMode(CameraViewEvent.Mode.SELFIE);
        }
    }

    @SubscribeEvent
    public static void ready(CameraImageEvent.Ready event) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal("照片 " + event.getExposureId()
                    + "：" + event.getImage().width() + "×" + event.getImage().height()), false);
        }
    }
}
```

服务端 Open/Tick 的场景覆盖使用 `event.trySetScene(targetDimension, null)`；目标须已注册、与来源不同，且仍需有效的原滤镜观察会话。要关闭观察，在所需端的 Tick 调 `requestClose()`。自定义图片处理可实现独立的 Exposure `Image`，复制或生成自己的像素后交给 `replaceImage(...)`；原接口只有尺寸、像素读取和关闭契约，不存在通用 `Image.copy()` 或写像素 API。

当前源码还带有 `camera.CameraEventTest` 聊天测试监听器，`ENABLED = true`；仅用于核对实际事件顺序，观察 Tick 消息限频到每 20 个玩家刻，事件本身没有被限频。这些聊天测试消息不受 `enableLogging` 或 `core.enableWarning` 控制。它是当前开发测试辅助，不是公共 API 或配置开关。

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

该事件不提供 Frame、图片 ID、出镜列表或触发类型；要控制拍摄及读取照片信息，使用上面的 `CameraCaptureEvent`、`CameraSubjectsEvent` 和 `CameraFrameEvent`。它继续用于安全传送附魔等“玩家已经换维”的后续效果，不被 `CameraTransferEvent.After` 取代。外部接入只需监听，不应自行构造并发布它来模拟拍照。

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
| `shuttershadow:exposure_failure` / 曝光失效-相机 | I | 默认计划为 `NO_IMAGE`：不写胶卷帧、不创建图片、不截图或上传，仍保留本模组拍摄事件、快门、原生统计/进度及维度胶卷传送。下界要塞宝箱获得。 |
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

### Exposure 原事件替换与胶卷

加载本模组后，当前 Exposure 相机拍摄路径中的 `ModifyEntityInFrameDataEvent`、`ModifyFrameExtraDataEvent` 和 `FrameAddedEvent` **不再派发**，统一使用本模组的新事件，不保留旧事件桥接。这个替换覆盖普通相机和维度相机，不只对装滤镜的相机生效；依赖这些原事件的拓展需要适配。它没有关闭 NeoForge 的其他事件，也没有删除 Exposure 的原始帧数据生成、统计、进度和实体拍摄行为。

曝光失效通过本模组 Before 的输出计划及局部拍摄钩子跳过写卷、截图请求和上传，适用于普通拍照、手持、手动支架及红石支架；对象选择与 Frame 事件仍能执行。无胶卷、满卷等原生拍摄资格检查保留，默认 `NO_IMAGE` 不消耗帧数。`CameraFrameEvent` 管理照片数据，`CameraTransferEvent` 管理每个主体，`CameraCaptureEvent.Completed` 管理整个事务，`CameraDimensionTeleportEvent` 保留成功玩家换维通知。

| 胶卷/入口 | 当前边界 |
| --- | --- |
| 玩家维度胶卷 | 手持物理自拍先传送再自拍；支架从同一拍摄名单传送玩家，个人同意和传送保护保留。 |
| 生物维度胶卷 | 默认从目标维度选首个非玩家生物，`CameraSubjectsEvent` 可选择多个有效候选；图片验收后传到来源，免成片无需图片也可完成。普通实体跨维返回新实例。 |
| 红石维度支架 | 默认拍原维度、隐藏玩家，不渲染或等待目标照片；维度胶卷保留玩家/生物传送。Before 可改变该次照片场景或免成片，不能假定它永远没有客户端图像。 |

## 9. 客户端显示与可选渲染兼容

### 相机附件提示框

通过 NeoForge `RenderTooltipEvent.GatherComponents` 与原生 `TooltipComponent`/`ClientTooltipComponent` 显示附件槽位。顺序和数量来自 `CameraItem.getAttachments()`，普通相机依次为胶卷、闪光灯、镜头、滤镜。空槽只显示无边框、无占位图案的深色形状；已装附件显示原物品图标及原生数量、耐久或胶卷进度。

这是只读物品提示框，不提供拖放或额外背包界面。尊重 `HIDE_TOOLTIP`/`HIDE_ADDITIONAL_TOOLTIP`，右键仍使用 Exposure 原界面。

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

当前 NeoForge payload 协议标识为 **`16`**（`ShuttershadowNetwork.PROTOCOL_VERSION`），相机与内核消息共用，消息 ID 使用 `shuttershadow:*`。客户端与服务端需安装兼容发行包且必需通道、协议标识匹配，由 NeoForge 协商判断兼容；没有额外内核版本比较、容忍标记或拒绝开关。

配置阶段用空就绪请求/回执记录真实连接，保证初始位置包解码时可判断扩展协议能力；此时玩家对象可能尚未创建。该步骤不携带版本或配置数据。登录维度同步继续保留，登出清理连接就绪状态。

原维度主要由原版区块/实体流程同步，额外世界包用维度标记重定向，原版与相机区块批次使用独立回执。观察会话使用递增序号和当前实际滤镜目标校验，迟到旧场景不能替换新目标；照片使用独立负序号。数据包滤镜注册表沿用 Exposure 原生同步。

远实体同步只刷新有额外订阅或待清理观察者的世界，关闭订阅后仍完成解除配对。无缝玩家传送和服务端停机释放继续保留，载具不再随相机/API 玩家传送；最后伤害者和攻击目标不由本模组额外清理，不再使用全局生物 tick 战斗引用 Mixin。

网络消息、维度数字 ID 和客户端多世界切换属于内核实现，扩展不应直接拼包替代公共 API。修改包字段、Codec 或双方世界语义时需更新两端并维护协议标识。

## 11. 维护验证

修改 API 契约时同步检查本文、实际公开签名与调用者；重点验证服务器线程、玩家/实体身份、返回值、事件触发时机及加载器释放。GPU 光影、多人生物/载具及真实世界生成效果仍需游戏验证。

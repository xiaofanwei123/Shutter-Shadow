# Shuttershadow API 与扩展 Wiki

本文面向接入其他模组、制作数据包和调整服务器的维护者。对应 Minecraft **1.21.1**、NeoForge **21.1.252**、Exposure **1.9.19** 的当前源码。完整类、方法和相机调用过程见 [代码 Wiki](CODE.md)，安装、启动和兼容概览见 [README](../README.md)。

## 1. 接入边界

公共 Java API 都在 `com.xfw.shuttershadow.api`，随发行 JAR 提供：

| 类 | 用途 | 是否改变世界状态 |
| --- | --- | --- |
| `SeamlessTeleportation` | 玩家和实体的即时无缝传送 | 是 |
| `ChunkLoader` | 描述额外加载的正方形区块区域 | 构造不加载；就绪检查只读取 |
| `ChunkLoading` | 注册和释放服务端保活或玩家额外区块订阅 | 是 |
| `DimensionFilters` | 创建滤镜、解析数据包路由、换算坐标 | 创建修改新物品栈；解析和换算不传送、不加载区块 |

接入项目需要把 Shuttershadow JAR 放入编译依赖；实际运行还需要 Shuttershadow 及 Exposure。无缝玩家跨维和向玩家同步异维度区块要求**客户端与服务端都安装**本模组及必要依赖。Sodium、Iris 仍是可选客户端模组，不是这些 API 的前置。

`core`、`client`、`network`、`mixin`、`access` 和 `util` 是内部实现，公开可见不等于稳定扩展接口。外部模组优先调用上述四个类，不直接操作相机事务、远程世界注册表或内部传送管理器。本项目当前没有单独发布 API Maven 制品。

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

玩家正在乘坐的**直接载具**，例如船或矿车，会随玩家跨维并恢复乘坐。其他乘客留在原维度；不会递归搬运整棵乘客树。其他原维度观察者会清除旧载具，当前传送玩家保留客户端载具用于无缝交接。普通实体传送会解除骑乘关系，没有玩家的自动携带规则。不要把已跨维重建的旧实体引用继续用于后续操作。

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
| `isFullyLoaded(server)` | 必须在所属服务器线程调用。检查区域全部区块的服务端完整 tick/实体加载状态，维度不存在返回 `false`；不主动加载、不阻塞，也不检查客户端收包、地形编译、光照或截图是否完成。 |
| `foreachChunkPos(consumer)` | 按 X 偏移外层、Z 偏移内层的顺序枚举包含边界的区域，传入维度、区块 X/Z、与中心的切比雪夫距离 `max(abs(dx), abs(dz))`；空 consumer 抛 `NullPointerException`。 |
| `toString()` | 输出 `(维度ID x z radius)`，便于日志记录。 |
| `equals(...)`、`hashCode()` | record 按值比较，可用于区域比较；不能用同值新对象替代注册时的对象进行释放。 |
| `ChunkPosConsumer.consume(dimension, x, z, distanceToSource)` | 枚举回调；距离单位为区块，中心为 0。 |

纯构造、字段读取和区域枚举不访问游戏世界；区域很大时枚举仍会占用调用线程时间。

## 4. 额外区块订阅：ChunkLoading

### 四个公共方法

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

API 不会自动读取相机专用 `max_view_distance` 来裁剪外部 loader。它沿用内核加载开关和票据活跃等级：禁用 `enableRemoteChunkLoading` 后不添加新的主动加载票据，区域可能一直不就绪；不要把它当成无条件完成的 future。

## 5. 滤镜和坐标：DimensionFilters

### 全部公共方法

| 方法 | 输入、输出和边界 |
| --- | --- |
| `create(ResourceLocation targetDimension)` | 创建数量为 1 的 `shuttershadow:dimension_filter`，写入目标组件。空目标抛 `NullPointerException`；不会检查目标维度或路由是否存在。需要物品注册已完成。 |
| `target(ItemStack filter)` | 只读取本模组滤镜的 `shuttershadow:dimension_filter_target` 组件；null、其他物品、未指定目标返回 `null`。这不是完整路由解析。 |
| `resolve(@Nullable RegistryAccess registries, ItemStack filter, ResourceLocation sourceDimension)` | 使用 Exposure 的完整物品谓词选择滤镜条目，再取当前来源维度的路由；没有有效路由返回 `null`。null/空物品或空来源返回 `null`。注册表未就绪、异常或没有可用谓词路由时使用本地路由文件回退。 |
| `horizontalScale(@Nullable Route route, Level source, Level target)` | 返回实际 X/Z 倍率。显式坐标比例为 `source.dimensionType().coordinateScale() / route.coordinateScale()`；路由为空或比例省略时使用两个维度类型的原生传送倍率。 |
| `mapAbsolute(Vec3 position, double scale)` | 返回 `(position.x*scale, position.y, position.z*scale)`。保留脚底 Y，不加眼睛偏移。 |
| `mapRelative(Vec3 delta, Vec3 targetOrigin, double scale, double yOffset)` | 返回 `targetOrigin + (delta.x*scale, delta.y+yOffset, delta.z*scale)`，用于固定取景基准后的相对位移。 |

这组方法可在客户端与服务端使用，不会启动相机、发网络包、加载区块或传送实体。使用当前逻辑线程拥有的物品和注册表；不要同时从其他线程修改同一个 `ItemStack`。解析可能触发本地 JSON 文件读取，不是纯数学函数。

`horizontalScale`、`mapAbsolute`、`mapRelative` 没有额外有限数或越界检查；所需世界、向量为空会由底层访问抛出异常。把换算结果交给传送 API 时，后者会检查坐标有限性。

### Route record

```java
public record Route(ResourceLocation filter, ResourceLocation dimension, double coordinateScale)
```

`filter()` 是**物品注册 ID**，例如 `shuttershadow:dimension_filter`；不是 Exposure 滤镜数据条目 ID。`dimension()` 是解析后的实际目标，允许与物品用于选择变体的目标组件不同。`coordinateScale()` 是**目标坐标尺度**，下界通常为 `8`，不是实际乘数；`NaN` 表示省略比例、改用目标维度类型。

构造时 filter/dimension 为空抛 `NullPointerException`；比例除 NaN 之外必须为有限正数，否则抛 `IllegalArgumentException`。record 提供不可变字段读取、按值相等和哈希。

主世界坐标尺度 1，下界坐标尺度 8，因此主世界→下界 X/Z 乘 `1/8`，下界→主世界乘 `8`。Y 保持不变。数据包中显式写 `coordinate_scale: 8` 是指定目标尺度，而不是让所有坐标乘 8。

### 从滤镜路由传送的完整例子

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
        DimensionFilters.Route route = DimensionFilters.resolve(
                source.registryAccess(), filter, source.dimension().location());
        if (route == null) return false;
        ServerLevel target = source.getServer().getLevel(
                ResourceKey.create(Registries.DIMENSION, route.dimension()));
        if (target == null) return false;
        double scale = DimensionFilters.horizontalScale(route, source, target);
        Vec3 position = DimensionFilters.mapAbsolute(player.position(), scale);
        return SeamlessTeleportation.teleportPlayer(player, target, position) != null;
    }
}
```

调用者在服务器线程执行 `transfer`。例子只演示路由和移动，不打开相机、不检查安全落点、不模拟拍照事件。

## 6. 数据包维度滤镜

### 文件位置和 ID

```text
data/shuttershadow/dimension_filter/<目标维度命名空间>/<目标维度路径>.json
assets/shuttershadow/models/item/dimension_filter/<目标维度命名空间>/<目标维度路径>.json
```

内置目标是 `minecraft:overworld`、`minecraft:the_nether`、`minecraft:the_end`。例如 `example:moon/inner` 对应：

```text
data/shuttershadow/dimension_filter/example/moon/inner.json
assets/shuttershadow/models/item/dimension_filter/example/moon/inner.json
```

模型是资源包内容，路由是数据包内容，两者使用相同目标路径；目标世界本身必须由其他数据包或模组注册。内置统一滤镜的物品 ID 是 `shuttershadow:dimension_filter`，组件 `shuttershadow:dimension_filter_target` 储存目标 ID。

### 可用 JSON 示例

放到 `data/shuttershadow/dimension_filter/example/moon/inner.json`：

```json
{
  "predicate": {
    "items": "shuttershadow:dimension_filter",
    "components": {
      "shuttershadow:dimension_filter_target": "example:moon/inner"
    },
    "predicates": {
      "shuttershadow:camera_dimension": {
        "routes": {
          "minecraft:overworld": {
            "target_dimension": "example:moon/inner"
          },
          "minecraft:the_nether": {
            "target_dimension": "example:moon/inner"
          }
        }
      }
    }
  },
  "shader": "shuttershadow:shaders/post/neutral.json"
}
```

`routes` 必须非空，以**来源维度**为键，每条必填 `target_dimension`。省略 `coordinate_scale` 就使用实际目标维度类型尺度；显式字段必须是有限正数。例如下界路由：

```json
{
  "target_dimension": "minecraft:the_nether",
  "coordinate_scale": 8.0
}
```

`shader` 是 Exposure 滤镜的后处理资源，不是 Iris 光影包。`shuttershadow:camera_dimension` 只携带路由元数据；实际物品选择仍由外层 `items`、`components` 和其他完整谓词决定。自定义普通物品也可通过 Exposure 滤镜格式提供路由，但本模组 `target()` 只读取自己的统一维度滤镜。

路由解析保持 Exposure 的**首个完整物品谓词匹配**语义，不会跳过先匹配的普通滤镜去找后续维度滤镜。缓存只保存注册表快照的候选条目列表，每次仍判断物品栈全部条件；不缓存可变相机/滤镜的匹配结果。

首个匹配条目没有当前来源路由时，解析仍会尝试本地 JSON 回退。因此“从数据包中省略一条来源路由”不必然等于禁用该来源，原版三维度可能被本地默认表补上；需要禁用时也删除本地回退表中的对应路由。

### 与 Exposure 注册表的关系

新目录映射进 Exposure 原有 `exposure:filter` 动态注册表，不新建另一套同步协议。上述月球文件对应的注册表条目 ID 为 `shuttershadow:example/moon/inner`；末地为 `shuttershadow:minecraft/the_end`。

Exposure 标准 `data/<namespace>/exposure/filter/*.json` 仍由原生格式加载。标准目录和新目录产生同 ID 时按数据包堆叠优先级选择；同一包同优先级时新目录优先。文件仍按原生 ResourceLocation 排序，客户端 known-pack 本地读取也复用目录映射。

本模组**不兼容旧维度滤镜目录或旧谓词 ID**。这与 Exposure 自身的标准目录是不同事项：标准 Exposure 数据包仍可使用。修改动态注册表条目后重新进入世界；专用服务器重新启动。新增目标模型属于客户端资源包，需让客户端获得对应资源。

内置新目标可被创造栏自动列出，但条目的外层 `items` 必须显式包含统一维度滤镜，且包含本模组路由谓词；创造栏按这些路由的目标维度去重生成滤镜。

## 7. 配置文件与作用域

NeoForge 原生配置界面从模组列表进入，不依赖 Cloth Config。**作用域不是所有配置都同步**：

| 文件 | 作用域 | 真实读取位置与同步 |
| --- | --- | --- |
| `shuttershadow-server.toml` | `SERVER` | 当前 NeoForge 默认在实例 `config/`；已有世界 `serverconfig/shuttershadow-server.toml` 时世界文件优先。服务器权威，连接世界时由 NeoForge 同步。 |
| `config/shuttershadow-client.toml` | `CLIENT` | 当前客户端个人设置；支架同意偏好由本模组另发消息同步到服务器。 |
| `config/shuttershadow-core.toml` | `COMMON` | 每个物理端各自本地加载，不随服务器同步。客户端配置控制本机渲染和提醒；服务端配置控制本机服务端票据、握手、日志。单人主机两种职责位于同一进程。 |
| `config/shuttershadow_dimensions.json` | 本模组 JSON 回退路由 | 本地按文件修改时间重读，不是 NeoForge 同步配置。优先使用 Exposure 数据包；用于注册表未就绪或无可用谓词路由时。多人统一规则优先发数据包。 |

内核旧 JSON 不读取、不迁移。当前已有 TOML 数值不会因为默认值修改而自动重置。

### SERVER：相机范围

| 完整键 | 默认、范围、单位 | 作用 |
| --- | --- | --- |
| `dimension_camera.max_view_distance` | **8**；**3～32 区块半径** | 统一限制手持和手动支架的目标维度订阅与绘制。实际距离还取客户端请求视距和服务器视距的较小值；不是区块总数，不修改普通视频视距。 |
| `camera_stand.stand_player_radius` | 8；1～64 方块 | 搜索原维度支架附近可能出镜的玩家，然后应用视锥、焦距、遮挡及个人传送同意。影响手动和红石支架玩家胶卷名单，不是目标画面视距。 |
| `mob_dimension_film.capture_radius` | **16**；**1～16 方块** | 扩展目标相机方块搜索盒的 X/Y/Z，再筛选生物；手持、手动支架、红石支架共用，不按取景视距缩减。 |

服务端相机上限通过 `RemoteSceneStartS2C.maxRenderDistance` 告知客户端，服务端同时按上限限制真实区块订阅；客户端绘制也取上限。正在观察的会话会在服务端 tick 中更新。客户端请求距离小于配置最小值时仍可更小：配置允许最小 3 **不等于强制每个人至少加载 3**。

半径 3 定义最多 7×7 区块的订阅窗口，实际仍受客户端请求及服务器视距限制。生物搜索 16 方块可越过相机中心区块边界，照常搜索邻区块，不截成中心 1 区块。支架生物扫描还可单独保活扫描区域；扫描对象必须实际存在于服务端。这个范围不是保证范围内每个生物都能选中，仍受镜头和遮挡判定。

照片使用玩家当前相机画面和已经同步的出镜实体，不为了照片把完整合影窗口加载齐；玩家传送不增加目的地区块等待。生物支架事务仍可等待获取目标实体必需的扫描区块，选中后只保活该实体所在区块直到上传完成或取消。

### CLIENT：个人传送偏好

| 完整键 | 默认 | 作用 |
| --- | --- | --- |
| `camera_stand.accept_stand_dimension_film_teleport` | `true` | 是否同意出镜时被玩家维度胶卷支架传送；连接后同步给服务器。拒绝不关闭相机观察、拍照或玩家投影，不用于 `/tps` 权限控制。 |

### COMMON：内核配置全部选项

以下键位于 `shuttershadow-core.toml` 顶层。

| 键 | 默认 | 生效端和含义 |
| --- | --- | --- |
| `enableClientPerformanceAdjustment` | `true` | 客户端原版地形渲染用近期帧率/可用内存，每 5 秒评估并缩短相机绘制距离；不超过服务端上限，不改普通世界视频设置。Sodium 自有地形流程不保证受此项控制。 |
| `clientTolerantVersionMismatchWithServer` | `false` | 客户端在内核握手中表示允许主/次协议版本不同；不转换数据包，不绕过 NeoForge 必需通道格式检查，重新连接时生效。 |
| `doCheckGlError` | `false` | 客户端额外检查 OpenGL 错误并记录日志；用于诊断，不修复 GPU 错误，增加检查开销。 |
| `saveMemoryInBufferPack` | `false` | 客户端减小新建原版区块网格缓冲的初始容量，容量不足仍增长。已有缓冲不会变小，Sodium 自有缓冲不由此控制；重启客户端使新分配一致。 |
| `enableWarning` | `true` | 客户端游戏内内核提醒总开关；内存检测、诊断日志继续执行。缺少服务端协议的连接错误不是可屏蔽的普通提醒。 |
| `serverSideNormalChunkLoading` | `true` | 服务端选择额外区块票据活跃级别：开为实体/方块 tick，关为方块 tick。关闭不取消订阅和票据，生物可能不更新；退出世界后修改，重新进入使新增/释放票据级别一致。 |
| `chunkPacketDebug` | `false` | 客户端额外记录跨维度区块载入/卸载日志，视距高时日志量大；不改变同步算法。 |
| `enableRemoteChunkLoading` | `true` | 服务端是否添加相机额外主动加载票据；关时依赖其他原因已加载的区块，可能缺地形或让实体扫描无法就绪；原版玩家加载保留。 |
| `serverTolerantVersionMismatchWithClient` | `false` | 服务端放宽内核主/次协议版本检查，不转换消息，重新连接时生效。 |
| `serverRejectClientWithoutShuttershadow` | `true` | 专用服务端额外拒绝没有 NeoForge/维度协议能力的客户端；关闭只跳过这一额外检查，必需 payload 通道仍保留，不提供原版客户端支持。 |
| `serverTeleportLogging` | `false` | 服务端记录玩家无缝换维维度和坐标；只改变日志，不增加等待或改变传送结果。 |
| `disabledWarnings` | `[]` | 客户端按 ID 屏蔽提醒；已有聊天消息不删除。ID：`iris`、`nvidia`、`low_max_memory`、`memory_not_enough`、`mod_version_mismatch`。 |

### 本地路由回退格式

`config/shuttershadow_dimensions.json` 示例只定义统一滤镜“下界目标”从主世界观察的路由：

```json
{
  "filters": {
    "shuttershadow:dimension_filter": {
      "targets": {
        "minecraft:the_nether": {
          "routes": {
            "minecraft:overworld": {
              "target_dimension": "minecraft:the_nether",
              "coordinate_scale": 8.0
            }
          }
        }
      }
    }
  }
}
```

最外层按滤镜**物品 ID**，`targets` 按本模组滤镜组件目标，再按来源维度选择。route 值与数据包复用同一个 Codec；省略比例使用维度类型。不存在文件时生成三种原版维度的默认路由；读取失败使用默认回退。此 JSON 不是旧兼容文件，不与数据包高优先级路由合并为第二套注册表。

## 8. 附魔、拍摄事件和胶卷边界

| 内容 | 当前效果 |
| --- | --- |
| `shuttershadow:exposure_failure` 曝光失效 | 有胶卷且通过 Exposure 原生资格判断时仍执行拍摄事件、快门、统计/进度；不写胶卷帧、不创建图片、不请求截图/上传，玩家和生物维度胶卷仍传送。手持、手动支架、红石支架均适用。 |
| `shuttershadow:narcissism` 自恋狂 | 每次打开手持相机默认正面自拍，可切回远景，关闭恢复原视角；支架没有自拍，所以不改变支架。 |
| 玩家维度胶卷 | 手持物理自拍沿用先传送再自拍；支架从同一拍摄名单传送，同意偏好和保护仍有效。 |
| 生物维度胶卷 | 从目标维度选取镜头内符合条件的首个非玩家生物，图片验收后传到相机原维度；曝光失效时无图片也可完成事务。普通实体跨维返回新实例。 |
| 红石维度支架 | 拍原维度、不显示玩家；普通胶卷不渲染或等待目标画面。维度胶卷完成照片事务后执行玩家/生物传送，曝光失效保留传送且不成片。 |

两个附魔都是一级、相机专用、可叠加的珍宝诅咒。创造栏提供附魔书；普通图书管理员附魔书交易池可获得，经铁砧附到相机；不进入附魔台候选集合，砂轮不清除诅咒。其他数据包可改这些标签和获得方式。

曝光失效继续触发 Exposure 的 `addNewFrame/onFrameAdded` 事件边界，但没有实际图片和新增胶卷帧。其他模组监听帧事件时不能假定一定能取到该曝光的图像。无胶卷、满卷等原生拍摄资格检查仍保留；曝光失效不消耗帧数。

## 9. 网络协议和版本要求

NeoForge payload 注册版本是 **`12`**（`ShuttershadowNetwork.PROTOCOL_VERSION`），相机消息和内核消息共用该版本，消息 ID 使用 `shuttershadow:*`。连接阶段还有独立的内核运行时握手 **`1.0.0`**（`PlatformBridge.getCoreProtocolVersion()`）；这两个数字用途不同，不是模组文件版本。

两端应使用同一版本发行包。握手允许 patch 差异、默认拒绝 major/minor 差异；上述宽容配置只放宽此项内核检查，不能让不同 payload 格式兼容。NeoForge 注册消息通道不是 optional，关闭“拒绝缺少维度协议的客户端”也不代表纯原版或未安装本模组客户端可连接。

原维度由 Minecraft 原生区块/实体发送；相机额外世界包由维度标记重定向，原版与相机区块批次使用独立回执。观察会话含递增序号及滤镜完整路由校验，迟到旧场景不会替换新滤镜目标；照片使用独立负序号。数据包滤镜注册表同步继续使用 Exposure 原生机制。

协议、维度数字 ID 和客户端多世界切换是内核细节，外部扩展不应直接拼网络消息替代 API。需要增加消息字段、修改 StreamCodec 或两端世界语义时，同步更新两端和协议版本，并检查 [代码 Wiki](CODE.md) 的网络及 Mixin 调用链。

## 10. 维护验证

API 对应回归在 `tools` 中单独运行，不打入模组，也不在游戏启动时执行：

- `test_seamless_teleportation_api.ps1`：输入、保护、身份及返回位置契约。
- `test_seamless_teleport_command.ps1`：注册、权限、坐标、部分失败、玩家/载具批量选择。
- `test_chunk_loader.py`：就绪状态与区域边界。
- `test_dimension_filters_api.py`：完整物品谓词、路由 Codec、新目录、数据包堆叠与 known-pack 读取。
- `test_camera_route_identity.py`：快速换滤镜、迟到场景、客户端/服务端视距上限。
- `test_entity_search_range.ps1`：生物方块范围与嵌套查询恢复。
- `test_core_native_config.ps1`：TOML、默认配置、重载和提醒开关。

修改 API 契约时同时更新本文、对应生产调用和相关回归。GPU 光影、多人生物/载具和真实区块生成效果仍需游戏验证。

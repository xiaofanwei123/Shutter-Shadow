# Shutter Shadow

Minecraft **1.21.1 / NeoForge** 的 Exposure 维度相机扩展。通过相机观察其他维度、拍摄当前取景画面，并使用维度胶卷无缝传送玩家或带回生物。

## 玩法与功能

将维度滤镜放入 Exposure 相机的滤镜槽，即可通过手持相机或手动操作的支架相机观察目标维度。观察期间玩家仍在原维度；安装维度胶卷并拍摄，才会触发对应传送。

| 附件或功能 | 效果 |
| --- | --- |
| 维度滤镜 | 所有变体共用 `shuttershadow:dimension_filter`，通过物品组件指定目标维度。默认提供主世界、下界和末地滤镜，可用数据包扩展。 |
| 玩家维度胶卷 | 手持自拍传送自己；支架拍摄传送原维度中位于搜索范围、符合镜头条件且同意支架传送的入镜玩家。 |
| 生物维度胶卷 | 在目标维度搜索镜头内未被遮挡的非玩家生物，将首个符合条件的生物传送到相机所在维度。 |
| 附件提示框 | 鼠标悬停相机时显示附件槽，默认包括胶卷、闪光灯、镜头和滤镜。空槽仅显示深色背景，已装附件显示物品及数量、胶卷进度等原生装饰；不增加操作界面。 |

本模组创造物品栏提供已注册的滤镜变体、两种维度胶卷及各级相机附魔书。默认下界滤镜还可用 **8 个玻璃围住 1 个黑曜石**合成。

手持和手动支架的照片采用玩家当前看到的相机画面，不等待整个取景范围的区块全部加载。红石触发带维度滤镜的支架时，拍摄的是**支架所在维度**，照片画面和元数据排除玩家，然后完成维度胶卷传送；它不拍摄目标维度。没有维度滤镜的普通 Exposure 支架保留原生拍摄行为。

玩家跨维度采用无缝传送，不额外等待目的地周围 3×3 区块。玩家正在乘坐的直接载具，例如船或矿车，可随玩家跨维并恢复骑乘；其他乘客留在原维度，不递归传送整棵乘客树。无缝传送不会自动寻找安全落点，也不保证未生成的目的地立即显示完整地形。

**相机附魔**通过铁砧应用，可以同时存在，不在附魔台或原版村民交易中获得：

| 附魔 | 等级与效果 | 生存获取方式 |
| --- | --- | --- |
| 曝光失效-相机 | I，诅咒。拍摄不生成或上传图片、不增加胶卷帧数，保留快门、Exposure 拍摄事件、统计及维度胶卷传送。手持、手动支架和红石支架均有效；仍需装入可拍摄的胶卷。 | 下界要塞宝箱。 |
| 自恋狂-相机 | I，诅咒。每次打开手持相机时默认进入自拍，可手动切回远景；支架没有自拍功能，不受影响。 | 下界要塞宝箱。 |
| 安全传送维度-相机 | I～III，正面附魔。相机拍摄成功换维后，按相机等级为被传送玩家给予 **10／20／30 秒**的缓降及安全传送效果。手持、手动支架、红石支架均适用。 | 末地船宝箱，普通末地城宝箱不追加。 |

符合条件的宝箱有 **30%** 概率额外生成一本书，原宝箱内容保留；下界要塞的两种诅咒等概率，末地船的安全传送 I～III 等概率。诅咒不能用砂轮清除。

安全传送效果免疫没有直接或间接实体来源的伤害，例如虚空、火、岩浆、窒息、中毒和凋零持续伤害；实体攻击、投射物及 `/kill` 不受保护。它不会熄火、搬离虚空或改变落点。普通拍照、传送失败、同维移动，以及 `/tps` 不会自动触发此附魔。

**无缝传送指令**：

```mcfunction
/tps <目标实体> <目标维度> <x> <y> <z>
/tps @s minecraft:the_end 0 80 0
/tps @e[type=minecraft:pig,distance=..8] minecraft:overworld 0 80 0
```

需要权限等级 **2**，支持实体选择器、相对坐标 `~` 和局部坐标 `^`。坐标按命令源解析，是目标维度中的脚底位置，不自动按下界比例换算；指令通过本模组无缝传送 API 执行。

## 安装与兼容

需要 **Minecraft 1.21.1、Java 21、NeoForge 和 Exposure**。客户端与服务端均须安装 Shutter Shadow、Exposure 及 Exposure 所需前置，并使用一致的 Shutter Shadow 发行版本。当前开发基准为 **NeoForge 21.1.252 / Exposure 1.9.19**；模组声明要求 Exposure **1.9.19 或更新版本**，后续版本兼容性仍需核实。

将发行 JAR 放入 `mods` 目录。无需安装 Immersive Portals 或 Cloth Config API。

| 模组 | 当前开发检查版本 | 兼容说明 |
| --- | --- | --- |
| Sodium | 0.8.13，NeoForge 1.21.1 | 可选客户端模组，已适配其区块渲染上下文；不内嵌，也不是必需依赖。 |
| Iris | 1.8.14 Beta 1，NeoForge 1.21.1 | 可选客户端模组，相机沿用玩家当前光影设置。跨维度显示效果受光影包影响。 |
| Create | 6.0.10，Minecraft 1.21.1 | 加入开发运行环境，不是发行依赖。 |
| Sable | 2.0.6，NeoForge 1.21.1 | 加入开发运行环境，不是发行依赖。 |
| Immersive Portals | 不允许同时安装 | 跨维度内核已整合进本模组，明确声明与 `immersive_portals_core` 不兼容。 |

安装 Sodium 或 Iris 时，当前声明的最低版本分别为 **0.8.13** 和 **1.8.14 Beta 1**。原版渲染与可选模组采用各自的渲染路径；开发检查版本不代表对任意后续版本或所有光影包都已验证。

原版相机雾按相机绘制距离调整。光影包可能自行计算雾气，因此在游戏视距较大、相机范围较小时，地形边缘仍可能明显；本模组不修改具体光影包的雾算法。

## 配置

游戏内可从模组列表进入 Shutter Shadow 的原生配置界面。配置分为服务端玩法与加载、客户端渲染与个人偏好、本地日志三类。

| 文件 | 用途 |
| --- | --- |
| `config/shuttershadow-server.toml` | 服务端玩法和相机区块加载设置，由服务器同步给客户端；世界已有 `serverconfig/shuttershadow-server.toml` 时采用世界配置。 |
| `config/shuttershadow-client.toml` | 客户端渲染、游戏内警告和个人传送偏好。只有支架传送同意偏好需要上报服务器。 |
| `config/shuttershadow-core.toml` | 通用配置，仅保留本地日志总开关；客户端与服务端分别设置，不由服务器同步。 |

**服务端配置**：

| 完整键 | 默认值 | 范围与作用 |
| --- | --- | --- |
| `dimension_camera.max_view_distance` | `8` | `3～32` 区块半径，限制手持和手动支架的目标维度区块订阅及绘制。实际距离还受客户端请求和服务器视距限制。 |
| `camera_stand.stand_player_radius` | `8` | `1～64` 方块，支架玩家维度胶卷搜索原维度玩家的范围，仍需符合镜头条件及个人同意偏好。 |
| `mob_dimension_film.capture_radius` | `16` | `1～32` 方块，沿 X/Y/Z 扩展目标相机方块的生物搜索盒，随后检查镜头和遮挡；不随相机视距缩小。 |
| `core.serverSideNormalChunkLoading` | `true` | 选择额外区块票据的活跃等级：开启时更新实体与方块，关闭时仅要求方块更新，生物可能停止更新；不会取消票据。 |
| `core.enableRemoteChunkLoading` | `true` | 是否主动加载相机额外订阅的远维度区块。关闭后只能使用被其他原因加载的区块，画面可能缺少地形。 |
| `core.delayUnloadGenerations` | `4` | `1～120` 个订阅更新代数，每代约 13 游戏刻；超过设定代数后释放不再观察的区块。**不建议修改**：过小容易反复加载，过大增加内存占用；大量区块及断线等清理仍可提前执行。 |

相机视距配置控制的是**目标维度加载订阅和绘制上限**，不修改玩家普通世界的视频视距或原版模拟距离。半径 `8` 对应最多 `17×17` 区块的订阅窗口，实际订阅和当帧画出的范围还受其他限制。生物搜索以方块为单位，可跨越相机中心区块边界，不会被截成中心区块；仍须满足镜头条件。生物获取可能等待必需的扫描区块，玩家传送没有额外区块等待。

**客户端配置**：

| 完整键 | 默认值 | 作用 |
| --- | --- | --- |
| `camera_stand.accept_stand_dimension_film_teleport` | `true` | 是否接受支架玩家维度胶卷传送。关闭后仍可观察和拍照，不影响 `/tps`。 |
| `core.enableClientPerformanceAdjustment` | `true` | 根据近期帧率和可用内存缩短原版目标地形绘制距离，不修改普通世界视频设置；Sodium 自身地形流程不保证受此项控制。 |
| `core.doCheckGlError` | `false` | 额外检查 OpenGL 错误，用于诊断；增加检查开销，输出服从日志总开关。 |
| `core.saveMemoryInBufferPack` | `false` | 减小本模组远维度原版区块网格缓冲的初始分配，容量不足仍增长；已有缓冲不缩小，Sodium 自有缓冲不受控制，修改后建议重启客户端。 |
| `core.enableWarning` | `true` | 统一开启或关闭全部内核游戏内提醒，包括内存和 Iris 提醒；与日志开关独立。 |

**本地日志配置**：`shuttershadow-core.toml` 中的 `enableLogging` 默认 **`false`**，统一控制本模组主动写出的控制台和文件日志。开启后仍遵守日志级别，不自动开启 `DEBUG`；关闭不影响游戏内提醒、业务检查或 Minecraft、NeoForge、其他模组的日志。

已有有效配置值会保留，调整默认值不会覆盖旧设置。更改区块票据活跃等级前请退出世界，再重新进入。

## 数据包与滤镜

维度滤镜使用 Exposure 的滤镜定义格式，数据文件放在：

```text
data/shuttershadow/dimension_filter/<目标维度命名空间>/<目标维度路径>.json
```

例如 `data/shuttershadow/dimension_filter/minecraft/overworld.json`：

```json
{
  "predicate": {
    "items": "shuttershadow:dimension_filter",
    "components": {
      "shuttershadow:dimension_filter_target": "minecraft:overworld"
    }
  },
  "attachment_texture": "exposure:textures/gui/filter/stained_glass.png",
  "attachment_tint": "78A7FF",
  "shader": "shuttershadow:shaders/post/neutral.json"
}
```

`shuttershadow:dimension_filter_target` 指定目标维度。只写以上结构即可从其他维度观察主世界，无需来源路由或距离比例字段；目标维度必须在服务器实际存在，同维度不建立跨维度观察。

X/Z 按**来源与目标维度类型的原生坐标比例**换算，Y 不缩放：主世界→下界为 `1/8`，下界→主世界为 `8`，主世界与末地通常为 `1`。自定义维度采用其维度类型定义的比例。

`attachment_texture` 和 `attachment_tint` 控制相机附件界面的滤镜图标，颜色为十六进制 RGB，不给照片整体染色。`shader` 指定 Exposure 的照片后处理资源，本模组的 `neutral.json` 保持颜色；它不是 Iris 光影开关。

滤镜物品模型采用相同的维度路径，放在资源包中：

```text
assets/shuttershadow/models/item/dimension_filter/<目标维度命名空间>/<目标维度路径>.json
```

自定义纹理、模型和译名需要相应资源包。新增符合上述物品和目标组件谓词的滤镜定义后，创造物品栏会自动列出其目标变体。数据包配置及 Java API（无缝传送、区块加载、滤镜工具、相机换维事件）详见 [API 文档](wiki/API.md)。

## 上游与许可

本模组基于 [Exposure](https://github.com/mortuusars/Exposure) 的相机、附件、胶卷及拍摄流程扩展；Exposure 是独立安装的前置模组。附件提示框参考 Tide 的槽位显示思路，独立实现，不依赖 Tide。

跨维度内核移植并修改自 [Immersive Portals for NeoForge](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/tree/v6.0.7)，版本 **v6.0.7**，提交 `aede93a4865fe4aab5dd2781fb38ab3e5cecd63b`，原作者为 qouteall、Nick1st 及上游贡献者。本项目调整了包名、网络、同步与渲染实现，删除传送门功能，保留相机跨维度观察和无缝传送所需内核。

本模组原创部分采用 **All Rights Reserved**；上游移植及衍生部分继续遵循 **[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)**，不受原创部分的 All Rights Reserved 声明限制。上游来源和修改说明见 [NOTICE-immersive-portals.txt](src/main/resources/META-INF/NOTICE-immersive-portals.txt)。

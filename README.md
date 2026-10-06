# Shuttershadow

Minecraft **1.21.1 / NeoForge** 的 Exposure 维度相机扩展。用相机观察其他维度，
再通过维度胶卷无缝传送玩家或获取目标维度中的生物。

## 功能

- **维度滤镜**：手持相机和手动操作的支架相机可以观察、拍摄目标维度。照片采用当前取景画面，不等待完整视距内所有区块。
- **玩家维度胶卷**：手持自拍传送自己；支架拍摄传送符合镜头条件且接受支架传送的玩家。玩家跨维传送不额外等待目的地 3×3 区块，正在乘坐的船或矿车会跟随，其他乘客不自动跟随。
- **生物维度胶卷**：获取目标维度中符合镜头条件的最近非玩家生物，将其传送到相机所在维度。默认搜索范围为 16 方块，可设 1～16。
- **红石维度支架**：装有有效异维度滤镜时，拍摄支架所在维度，照片不显示玩家，随后执行维度胶卷传送；不渲染目标维度照片。普通 Exposure 支架沿用其原生拍摄，手动维度支架仍保留目标维度观察与拍摄。
- **曝光失效**：取消成片及胶卷加帧，保留 Exposure 拍摄事件和维度胶卷传送，支架也可触发。
- **自恋狂**：手持相机打开时直接进入自拍；支架没有自拍功能，不受此附魔影响。两种诅咒附魔书可从创造栏、图书管理员交易获取，并通过铁砧附上，不在附魔台生成。
- **无缝传送指令**：`/tps <目标实体> <目标维度> <x> <y> <z>`，权限等级 2，支持实体选择器、`~` 和 `^`。

例如 `/tps @s minecraft:the_nether 0 80 0` 将自己无缝传送到下界指定坐标。
指令使用公开 API，保留相机传送保护，不增加目的地区块等待。

## 安装与兼容

客户端与服务端均需安装 Shuttershadow、Exposure 及 Exposure 要求的前置。
当前开发版本为 **NeoForge 21.1.252 / Exposure 1.9.19**。

跨维度内核已整合进本模组，只注册 `shuttershadow` 一个模组入口。
**不需要 Immersive Portals，不能与其同时安装**；本模组不提供实体传送门、原版门透视或穿门碰撞。
Cloth Config 已移除，配置界面使用 NeoForge 原生实现，旧 JSON 配置和旧滤镜数据包目录不读取、不迁移。

| 可选模组 | 当前适配或开发检查版本 | 说明 |
|---|---|---|
| Sodium | 0.8.13，NeoForge 1.21.1 | 适配其区块渲染与跨维度上下文；发行模组不要求安装，也不内嵌 Sodium。 |
| Iris | 1.8.14 Beta 1，NeoForge 1.21.1 | 手持和手动支架沿用玩家当前光影设置。跨维度光影效果仍受光影包影响。 |
| Create | 6.0.10，Minecraft 1.21.1 | 加入开发运行环境，不是发行依赖。 |
| Sable | 2.0.6，NeoForge 1.21.1 | 加入开发运行环境，不是发行依赖。 |

Sodium 与 Iris 上述版本已完成针对性代码、字节码和运行类路径检查；
Create 与 Sable 已加入开发环境，并经过用户的阶段性实机测试。
这不代表任意光影包、更新版本或模组组合都已验证。光影观察可能增加首次维度加载开销，
部分光影包可能出现频闪或画面异常；可通过降低相机视距或调整光影设置比较。
红石拍摄原维度的行为用于避免后台跨维度光影截图的不稳定路径。

## 网络与配置

网络载荷使用 `shuttershadow:*`，当前注册协议版本为 **12**，另有内核配置握手版本 **1.0.0**。
多人游戏必须统一客户端与服务端模组版本；相机场景序号、目标维度和请求身份会共同校验，
用于拒绝过期场景和快速换滤镜后的晚到数据。
“允许协议版本差异”只放宽内核握手，不能保证不一致的数据包格式可用，也不跳过 NeoForge 必需通道校验。

| 配置文件 | 用途 |
|---|---|
| `config/shuttershadow-server.toml` | 服务端相机视距、支架玩家搜索范围、生物获取范围。世界目录已有 `serverconfig/shuttershadow-server.toml` 时优先采用它。 |
| `config/shuttershadow-client.toml` | 个人是否接受支架玩家维度胶卷传送，会同步给服务端。 |
| `config/shuttershadow-core.toml` | 本地内核性能、区块票据、握手、警告及调试开关；不作为服务器统一配置同步。 |

`dimension_camera.max_view_distance` 默认 **8**，允许 **3～32 区块半径**。
它同时限制手持与手动支架的目标维度加载与绘制，实际值还取客户端请求和服务器视距的较小值，
不会修改玩家普通世界的视频视距设置。半径 3 最多覆盖 7×7 区块，半径 8 最多覆盖 17×17。
生物获取范围以方块计，独立于取景视距。已有配置中的有效值会保留，默认值不会覆盖个人设置。

游戏内从模组列表进入 Shuttershadow 配置界面，各选项提供中文说明。
警告可用 `enableWarning` 关闭，或用 `disabledWarnings` 按 ID 屏蔽，诊断日志仍保留。
完整配置含义、数据包示例和扩展契约见 [API Wiki](wiki/API.md)。

## 开发与维护

使用 **JDK 21**，导入 Gradle 项目后刷新 IDEA 的 Gradle 模型，从顶部 **Client** 运行配置直接启动。
开发客户端默认加载 Sodium 和 Iris，无需传 `renderCompat` 参数；是否启用光影仍由游戏内设置决定。
Sodium 的开发专用合并包仅用于处理其官方发行包的嵌套主体，不修改下载缓存，也不会打入发行模组。

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runData
.\gradlew.bat verifyRendererRuntime
```

构建结果在 `build/libs/shuttershadow-1.0.jar`。`runData` 生成附魔、标签和中英配置翻译；
数据生成环境排除需要客户端渲染初始化的 Sodium、Iris 和 Exposure: Space，正常 Client 仍加载它们。

项目结构：`src/main` 是生产代码与资源，`src/generated/resources` 是需保留的生成资源，
`gradle` 包含 Wrapper 和开发渲染器处理，`wiki` 是 API 与扩展文档。
旧依赖源码副本、历史审查文档和迁移工具已移出项目。

- [API Wiki](wiki/API.md)：无缝传送、区块加载、滤镜、数据包与配置的使用契约。

## 上游与许可

跨维度基础来自 [Immersive Portals for NeoForge](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/tree/v6.0.7)，
版本 `v6.0.7`，提交 `aede93a4865fe4aab5dd2781fb38ab3e5cecd63b`，作者 qouteall、Nick1st 及上游贡献者。
移植部分遵循 Apache-2.0，不受原创部分 All Rights Reserved 声明限制。
原许可保留在 `src/main/resources/META-INF/licenses/immersive-portals.txt`，
来源与当前修改概述见 `src/main/resources/META-INF/NOTICE-immersive-portals.txt`。

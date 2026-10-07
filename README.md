# Shutter Shadow

Minecraft **1.21.1 / NeoForge** 的 Exposure 维度相机扩展。通过维度滤镜观察其他维度，再使用玩家维度胶卷将玩家无缝传送到取景位置，或使用生物维度胶卷把目标维度的生物带回来。

## 玩法与功能

| 附件或功能 | 效果 |
|---|---|
| 维度滤镜 | 放入 Exposure 相机的滤镜槽，手持和手动支架相机可观察目标维度。所有滤镜共用 `shuttershadow:dimension_filter`，由物品组件区分目标维度。 |
| 玩家维度胶卷 | 手持自拍传送自己；手动支架拍摄时，传送来源维度中入镜、位于配置范围内且接受支架传送的玩家。 |
| 生物维度胶卷 | 在目标维度搜索符合镜头和遮挡条件的非玩家生物，将首个符合条件的生物传送到相机所在维度。 |
| 曝光失效 | 一级诅咒附魔。取消照片生成、上传及胶卷加帧，保留 Exposure 拍摄事件、快门和维度胶卷传送；手持、手动支架、红石支架均有效，仍需满足原相机的胶卷拍摄条件。 |
| 自恋狂 | 一级诅咒附魔。手持相机打开时直接进入自拍；支架没有自拍功能，不受影响。 |
| 附件提示框 | 鼠标悬停相机时显示附件槽，默认包括胶卷、闪光灯、镜头、滤镜。空槽仅有深色背景；装入附件后显示物品、数量及胶卷进度。 |

两种诅咒附魔书可在本模组创造物品栏和图书管理员交易中获得，通过铁砧附到相机上，不在附魔台生成。

### 拍照与传送

- **手持、手动支架**：维度照片采用当前取景画面，不等待整个目标区块窗口全部加载完成。观察期间玩家实体仍在原维度，只有拍摄触发维度胶卷时才执行传送。
- **红石维度支架**：拍摄支架所在维度，照片画面及元数据排除玩家，然后执行维度胶卷传送；不渲染目标维度照片。没有维度滤镜的普通 Exposure 红石支架保留其原生行为。
- **无缝传送**：没有额外的目的地 3×3 区块等待。玩家跨维度时可携带船、矿车等直接载具并恢复骑乘；不递归传送整棵乘客树，其他乘客留在来源维度。

取景位置的 X/Z 按维度比例换算，Y 保持不变。例如主世界观察下界时，玩家移动 8 方块对应目标画面移动 1 方块，这是默认坐标比例的效果。

### 指令

```mcfunction
/tps <目标实体> <目标维度> <x> <y> <z>
/tps @s minecraft:the_end 0 80 0
```

权限等级为 **2**，支持原版实体选择器、相对坐标 `~` 和局部坐标 `^`。指令接入本模组无缝传送 API，坐标是目标维度中的脚底位置，不自动进行下界比例换算。

## 安装与兼容

客户端和服务端均需安装 Shutter Shadow、Exposure 及 Exposure 1.9.19。

| 模组名称 | 作用方式 | 当前适配或开发检查版本 | 说明 |
|---|---|---|---|
| Sodium | 兼容 | 0.8.13，NeoForge 1.21.1 | 适配其区块渲染与跨维度上下文；发行模组不要求安装，也不内嵌 Sodium。 |
| Iris | 兼容 |1.8.14 Beta 1，NeoForge 1.21.1 | 手持和手动支架沿用玩家当前光影设置。跨维度光影效果仍受光影包影响。 |
| Create | 可运行 |6.0.10，Minecraft 1.21.1 | 加入开发运行环境，不是发行依赖。 |
| Sable | 可运行 | 2.0.6，NeoForge 1.21.1 | 加入开发运行环境，不是发行依赖。 |
| Immersive Portals | 不兼容 | 任意版本 | 跨维度内核已整合进本模组 |

## 配置

| 文件 | 用途 |
|---|---|
| `config/shuttershadow-server.toml` | 服务端玩法配置。世界目录已有 `serverconfig/shuttershadow-server.toml` 时优先采用世界配置。 |
| `config/shuttershadow-client.toml` | 玩家个人是否接受支架玩家维度胶卷传送，连接期间同步给服务端。 |
| `config/shuttershadow-core.toml` | 本地内核性能、区块票据、警告和日志开关，不作为服务器统一配置同步。 |
| `config/shuttershadow_dimensions.json` | 本地滤镜路由兜底表。正常运行优先采用 Exposure 数据包注册表中的滤镜定义。 |

| 配置项 | 默认值 | 范围与作用 |
|---|---|---|
| `dimension_camera.max_view_distance` | `8` | `3～32` 区块半径，限制手持及手动支架目标维度的区块订阅、加载与绘制。实际范围还受客户端请求和服务器视距限制。 |
| `camera_stand.stand_player_radius` | `8` | `1～64` 方块，支架玩家维度胶卷的玩家搜索范围，仍需符合镜头条件。 |
| `mob_dimension_film.capture_radius` | `16` | `1～32` 方块，将目标相机方块的搜索盒沿 X/Y/Z 扩展，再按镜头和遮挡条件筛选生物。 |
| `camera_stand.accept_stand_dimension_film_teleport` | `true` | 客户端个人偏好，决定是否接受支架玩家维度胶卷传送。 |
| `enableWarning`（内核配置） | `true` | 统一开启或关闭全部内核游戏内提醒，包括 Iris、NVIDIA 和内存提醒。 |
| `enableLogging`（内核配置） | `true` | 统一开启或关闭本模组写出的控制台和文件日志；开启时仍遵守标准日志级别，与游戏内提醒开关独立。 |


已有配置中的有效值会保留，默认值不会覆盖已有设置。游戏内可从模组列表进入 Shutter Shadow 配置界面。`shuttershadow-core.toml` 中的 `enableWarning` 默认开启，统一控制全部内核游戏内提醒；关闭后停止显示新提醒，已显示的聊天消息不会被清除。

`enableLogging` 同样默认开启，独立控制本模组主动写出的控制台和文件日志，包括诊断日志。开启时仍遵守标准日志级别：区块逐包记录和传送诊断使用 `DEBUG`，不会因总开关开启而自动提升到 `INFO`。配置加载前暂不输出本模组日志，配置应用后按总开关执行，Log4j 重载后继续生效。

关闭日志不关闭游戏内提醒，也不取消业务检查、异常抛出或连接错误。Minecraft、NeoForge 和其他模组的日志，包括它们报告的异常或连接错误，不由此开关控制。`doCheckGlError` 仍单独决定是否进行额外 OpenGL 错误检查，其诊断输出也服从日志总开关。

## 数据包与滤镜

维度滤镜定义放在：

```text
data/shuttershadow/dimension_filter/<目标维度命名空间>/<目标维度路径>.json
```

对应物品模型放在：

```text
assets/shuttershadow/models/item/dimension_filter/<目标维度命名空间>/<目标维度路径>.json
```

例如主世界滤镜位于 `data/shuttershadow/dimension_filter/minecraft/overworld.json`。以下简写允许从任意不同的来源维度观察主世界：

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

目标维度由 `shuttershadow:dimension_filter_target` 组件指定。省略 `shuttershadow:camera_dimension` 时，不限制来源维度，目标坐标比例默认为 `1`；同维度不建立跨维度取景。

需要限制来源或设置比例时，添加 `routes` **数组**。下界滤镜示例：

```json
{
  "predicate": {
    "items": "shuttershadow:dimension_filter",
    "components": {
      "shuttershadow:dimension_filter_target": "minecraft:the_nether"
    },
    "predicates": {
      "shuttershadow:camera_dimension": {
        "routes": [
          { "source_dimension": "minecraft:overworld", "coordinate_scale": 8.0 },
          { "source_dimension": "minecraft:the_end", "coordinate_scale": 8.0 }
        ]
      }
    }
  },
  "attachment_texture": "exposure:textures/gui/filter/stained_glass.png",
  "attachment_tint": "5E0AC7",
  "shader": "shuttershadow:shaders/post/neutral.json"
}
```

每项必须写 `source_dimension`，来源不能重复，数组不能为空；`coordinate_scale` 可省略，默认 `1`，填写时必须是正有限数。显式路由未列出的来源无法使用该滤镜，不会被本地兜底表重新允许。只支持当前数组格式，不兼容旧的路由对象格式或逐条 `target_dimension` 字段。

实际 X/Z 倍率为 **来源维度类型的坐标比例 ÷ 配置的 `coordinate_scale`**。因此默认 `1` 不等于所有来源都按 1:1 移动：主世界→下界配置 `8` 时倍率为 `1/8`，下界→主世界简写时倍率为 `8/1`。Y 不参与缩放。

`attachment_texture` 和 `attachment_tint` 控制 Exposure 相机附件界面的滤镜图标，不给照片整体染色，也不替代物品模型。`shader` 是 Exposure 的照片后处理资源；本模组的 `neutral.json` 保持照片颜色，不是 Iris 光影开关。

完整字段、兜底表格式和 Java 扩展示例见 [API Wiki](wiki/API.md)。

## 上游与许可

原创部分采用 **All Rights Reserved**。跨维度基础来自 [Immersive Portals for NeoForge](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/tree/v6.0.7)，版本 `v6.0.7`，提交 `aede93a4865fe4aab5dd2781fb38ab3e5cecd63b`，作者 qouteall、Nick1st 及上游贡献者。移植部分遵循 **Apache-2.0**，不受原创部分的 All Rights Reserved 声明限制。

来源与当前修改概述见 [NOTICE-immersive-portals.txt](src/main/resources/META-INF/NOTICE-immersive-portals.txt)。
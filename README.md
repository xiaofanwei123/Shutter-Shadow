# English

Shutter Shadow is an addon for **Exposure** on **Minecraft 1.21.1 / NeoForge**. Look into other dimensions through a camera, photograph the current view, and use dimension film to seamlessly teleport players or bring creatures back.

Documentation: [API and datapacks](wiki/API.md) · [Code and call flows](wiki/CODE.md).

## Gameplay and features

<p>Place a dimension filter in an Exposure camera's filter slot to view its target dimension with a handheld camera or a manually operated camera stand. Viewing alone leaves you in your original dimension. Install dimension film and take a photo to trigger the corresponding teleportation.</p>

<table>
<thead><tr><th>Attachment or feature</th><th>Effect</th></tr></thead>
<tbody>
<tr><td>Dimension filter</td><td>All variants share the item ID <code>shuttershadow:dimension_filter</code>. A data component specifies the target dimension. Overworld, Nether and End filters are included; datapacks can add more.</td></tr>
<tr><td>Player dimension film</td><td>With a dimension filter installed, handheld selfies teleport the photographer to the target dimension; stand photos teleport players in the original dimension who are within the search range, meet the camera's visibility requirements, and have enabled stand teleportation consent.</td></tr>
<tr><td>Creature dimension film</td><td>Searches the target dimension for unobstructed non-player living entities in the camera's view and brings the first qualifying creature into the camera's dimension.</td></tr>
<tr><td>Attachment tooltip</td><td>Hover over a camera to see its attachment slots, including film, flash, lens and filter.</td></tr>
</tbody>
</table>

<p>The mod's creative tab lists registered dimension filter variants, both types of dimension film, and camera enchantment books for each available level.</p>

<p>Handheld and manually operated stand photos capture the current camera view. They do not wait for every chunk across the entire view range to finish loading.</p>

<p>Redstone-triggered stands with a dimension filter photograph the stand's own dimension. Players are excluded from the image and photo metadata, while dimension film still performs its teleportation. Redstone stands do not photograph the target dimension. Ordinary stands without a dimension filter photograph their own dimension with Exposure's normal player metadata.</p>

<p>Player dimension changes use seamless teleportation, with no additional wait for a 3×3 destination chunk area. Camera teleportation, the API, and <code>/tps</code> move only the selected entity after detaching its riding and passenger relationships. The original vehicle and other passengers remain at the source. If another mod prevents detaching, the transfer is rejected.</p>

<p>Seamless teleportation does not find a safe landing location or guarantee that an ungenerated destination will immediately display complete terrain.</p>

## Camera enchantments

<div class="spoiler">
<p>Apply these enchantments with an <strong>anvil</strong>. They can coexist and are not obtained from the enchanting table or vanilla villager trades.</p>
<table>
<thead><tr><th>Enchantment</th><th>Levels and effect</th><th>Survival acquisition</th></tr></thead>
<tbody>
<tr><td>Exposure Failure — Camera</td><td>I, curse. Does not generate or upload an image, or add a film frame. Preserves the shutter, Shutter Shadow's capture and frame events, Exposure statistics and advancements, and dimension film teleportation. Works with handheld, manual stand and redstone stand captures; usable film must still be installed.</td><td>Nether fortress chests.</td></tr>
<tr><td>Narcissism — Camera</td><td>I, curse. Opens a handheld camera in selfie mode by default; you can switch back manually. Stands have no selfie mode and are unaffected.</td><td>Nether fortress chests.</td></tr>
<tr><td>Safe Dimension Teleport — Camera</td><td>I–III. After a successful camera-triggered dimension change, gives teleported players Slow Falling and the mod's protection effect for <strong>10 / 20 / 30 seconds</strong>, based on the camera's enchantment level. Works with handheld, manual stand and redstone stand captures.</td><td>End ship chests only; regular End city chests do not receive the extra book.</td></tr>
</tbody>
</table>
<p>Each eligible chest has a <strong>30% chance</strong> to receive one extra book without replacing its original loot. The two fortress curses are equally likely; the three safety levels in End ship chests are equally likely. A grindstone cannot remove curses.</p>
<p>The safety effect protects against damage with no direct or indirect entity source, such as the void, fire, lava, suffocation, and ongoing poison or wither damage. It does not protect against entity attacks, projectiles or <code>/kill</code>.</p>
<p>The effect does not extinguish fire, move a player out of the void, or change the landing position. Ordinary photos, failed teleports, same-dimension movement, and <code>/tps</code> do not automatically trigger this enchantment.</p>
</div>

## Seamless teleport command

<div class="spoiler">
<pre><code>/tps &lt;entities&gt; &lt;dimension&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;
/tps @s minecraft:the_end 0 80 0
/tps @e[type=minecraft:pig,distance=..8] minecraft:overworld 0 80 0</code></pre>
</div>

## Installation and compatibility

<div class="spoiler">
<p>Requires <strong>Minecraft 1.21.1, Java 21, NeoForge, and Exposure</strong>. Install Shutter Shadow and Exposure on both the client and server. The mod declares Exposure <strong>1.9.19 or newer</strong>; compatibility with future versions still needs verification.</p>
<p>The current NeoForge network protocol identifier is <strong>16</strong>. Clients and servers need matching required channels and protocol identifiers; compatibility is negotiated by NeoForge.</p>
<table>
<thead><tr><th>Mod</th><th>Current development test version</th><th>Compatibility notes</th></tr></thead>
<tbody>
<tr><td>Sodium</td><td>0.8.13 for NeoForge 1.21.1</td><td>Optional client mod. Its chunk rendering context is supported; it is not a required dependency.</td></tr>
<tr><td>Iris</td><td>1.8.14 Beta 1 for NeoForge 1.21.1</td><td>Optional client mod. Cameras use the player's current shader settings. Cross-dimensional results depend on the shader pack.</td></tr>
<tr><td>Create</td><td>6.0.10 for Minecraft 1.21.1</td><td>Compatible.</td></tr>
<tr><td>Sable</td><td>2.0.6 for NeoForge 1.21.1</td><td>Compatible.</td></tr>
<tr><td>Immersive Portals</td><td>Must not be installed alongside Shutter Shadow</td><td>Incompatible.</td></tr>
</tbody>
</table>
<p>When installed, the declared minimum versions for Sodium and Iris are <strong>0.8.13</strong> and <strong>1.8.14 Beta 1</strong>, respectively. Vanilla rendering and optional renderer mods use their corresponding rendering paths. The listed versions do not imply that all future versions or shader packs have been tested.</p>
<p>Vanilla camera fog follows the camera's drawing distance. Shader packs may calculate fog independently, so terrain edges can remain visible when the game's view distance is larger than the camera range. This mod does not modify individual shader packs' fog algorithms.</p>
</div>

## Configuration

<div class="spoiler">
<p>Open Shutter Shadow's native configuration screen from the in-game mod list. Settings are divided into server gameplay/loading, client rendering/personal preferences, and local logging.</p>
<table>
<thead><tr><th>File</th><th>Purpose</th></tr></thead>
<tbody>
<tr><td><code>config/shuttershadow-server.toml</code></td><td>Server gameplay and camera chunk loading settings, synchronized to clients. An existing world's <code>serverconfig/shuttershadow-server.toml</code> takes precedence.</td></tr>
<tr><td><code>config/shuttershadow-client.toml</code></td><td>Client rendering, in-game warnings and personal teleport preferences. Only the stand teleportation consent preference needs to be sent to the server.</td></tr>
<tr><td><code>config/shuttershadow-core.toml</code></td><td>Common configuration containing the local logging master switch. The client and server configure it separately; it is not synchronized by the server.</td></tr>
</tbody>
</table>
<p><strong>Server settings</strong></p>
<table>
<thead><tr><th>Full key</th><th>Default</th><th>Range and purpose</th></tr></thead>
<tbody>
<tr><td><code>dimension_camera.max_view_distance</code></td><td><code>8</code></td><td>Chunk radius of <code>3–32</code>. Limits target-dimension chunk subscriptions and drawing for handheld and manually operated stand cameras. The actual range also follows the client's requested range and the server view distance.</td></tr>
<tr><td><code>camera_stand.stand_player_radius</code></td><td><code>8</code></td><td><code>1–64</code> blocks. Player dimension film search range around a stand in its original dimension; camera visibility and personal consent checks still apply.</td></tr>
<tr><td><code>mob_dimension_film.capture_radius</code></td><td><code>16</code></td><td><code>1–32</code> blocks. Expands the target camera block's creature search box along X/Y/Z, then checks camera visibility and obstruction. It is not reduced with the camera view distance.</td></tr>
<tr><td><code>core.serverSideNormalChunkLoading</code></td><td><code>true</code></td><td>Selects the activity level for extra chunk tickets. Enabled: entity and block ticking. Disabled: block ticking only, so creatures may stop updating. Tickets are still retained.</td></tr>
<tr><td><code>core.enableRemoteChunkLoading</code></td><td><code>true</code></td><td>Actively loads extra target-dimension chunks subscribed to by the camera. When disabled, only chunks loaded for other reasons are available, and terrain can be missing.</td></tr>
<tr><td><code>core.delayUnloadGenerations</code></td><td><code>4</code></td><td><code>1–120</code> subscription update generations, about 13 game ticks per generation. Chunks no longer being viewed are released after the configured number is exceeded. Changing this is not recommended: low values can cause repeated loading, while high values retain more memory. Large subscriptions and disconnect cleanup can release chunks earlier.</td></tr>
</tbody>
</table>
<p>The camera view limit controls <strong>target-dimension subscriptions and drawing</strong>, without changing the player's normal video view distance or vanilla simulation distance. Radius <code>8</code> covers at most a <code>17×17</code> chunk window; other limits can reduce the actual subscription and drawing range.</p>
<p>Creature search uses blocks and can cross the camera's center chunk boundary; it is not restricted to that chunk. Camera visibility checks still apply. Creature capture may wait for required search chunks; player teleportation has no additional chunk readiness wait.</p>
<p><strong>Client settings</strong></p>
<table>
<thead><tr><th>Full key</th><th>Default</th><th>Purpose</th></tr></thead>
<tbody>
<tr><td><code>camera_stand.accept_stand_dimension_film_teleport</code></td><td><code>true</code></td><td>Accept teleportation from player dimension film on a stand. Disabling it still allows viewing and photography; <code>/tps</code> is unaffected.</td></tr>
<tr><td><code>core.enableClientPerformanceAdjustment</code></td><td><code>true</code></td><td>Shortens vanilla target terrain drawing distance based on recent frame rate and available memory. Does not change normal-world video settings. Sodium's own terrain pipeline is not guaranteed to follow this setting.</td></tr>
<tr><td><code>core.doCheckGlError</code></td><td><code>false</code></td><td>Extra OpenGL error checks for diagnostics. Adds checking overhead; output follows the logging master switch.</td></tr>
<tr><td><code>core.saveMemoryInBufferPack</code></td><td><code>false</code></td><td>Reduces the initial allocation of the mod's vanilla remote chunk mesh buffers, which can still grow. Existing buffers do not shrink, and Sodium's buffers are unaffected. Restart the client after changing it.</td></tr>
<tr><td><code>core.enableWarning</code></td><td><code>true</code></td><td>Enables or disables all runtime in-game warnings, including memory and Iris notices. Independent from logging.</td></tr>
</tbody>
</table>
<p><strong>Local logging:</strong> <code>enableLogging</code> in <code>config/shuttershadow-core.toml</code> defaults to <code>false</code>. It controls this mod's log output on the local client or server, independently of in-game warnings and camera event chat messages.</p>
</div>

## Datapacks and dimension filters

<div class="spoiler">
<p>Dimension filters use Exposure's filter definition format. Put each data file at:</p>
<pre><code>data/shuttershadow/dimension_filter/&lt;target dimension namespace&gt;/&lt;target dimension path&gt;.json</code></pre>
<p>For example, <code>data/shuttershadow/dimension_filter/minecraft/overworld.json</code>:</p>
<pre><code>{
  "predicate": {
    "items": "shuttershadow:dimension_filter",
    "components": {
      "shuttershadow:dimension_filter_target": "minecraft:overworld"
    }
  },
  "attachment_texture": "exposure:textures/gui/filter/stained_glass.png",
  "attachment_tint": "78A7FF",
  "shader": "shuttershadow:shaders/post/neutral.json"
}</code></pre>
<p><code>shuttershadow:dimension_filter_target</code> specifies the destination. This format allows viewing the Overworld from other dimensions without a source-dimension list or a manually configured scale. The target dimension must exist on the server. A filter does not create a cross-dimensional view when its target is the current dimension.</p>
<p>Put filter item models in a resource pack using the corresponding path:</p>
<pre><code>assets/shuttershadow/models/item/dimension_filter/&lt;target dimension namespace&gt;/&lt;target dimension path&gt;.json</code></pre>
<p>Custom textures, models and translations need their corresponding resource pack. The creative tab automatically lists target variants registered with the above item and component predicate. Datapack definitions, optional source routes and Java extension contracts are documented in the <a href="wiki/API.md">API Wiki</a>.</p>
</div>

## Extension APIs and maintenance

<p>Public APIs cover seamless single-entity teleportation, extra chunk subscriptions and dimension filter utilities. Camera events on the NeoForge game event bus let addons configure opening mode and observation scenes, edit a capture plan before it is frozen, select photo or transfer subjects, change frame data or the client image, control individual transfers, and inspect actual completion results. The default player-film and creature-film flows still use internal transaction managers to coordinate dimension changes, screenshots and uploads.</p>
<p>The camera capture path replaces Exposure's three metadata/frame events with Shutter Shadow's events, including ordinary cameras without dimension attachments. Addons listening to those Exposure events need to use the new API. Exposure's native metadata generation, statistics, advancements and photographed-entity behavior continue to run. Exposure Failure uses the <code>NO_IMAGE</code> plan: it still produces logical capture/frame events and can teleport, but never produces an image-ready event.</p>
<p>The current source includes an enabled in-game event tracer, <code>CameraEventTest</code>. It prints opening, observation, capture and transfer stages to the relevant player's chat. <code>CameraEventTest.ENABLED</code> controls it independently of the logging and warning switches. See the <a href="wiki/CODE.md">Code Wiki</a> for class responsibilities, resource ownership and call flows.</p>

## Q&A

<div class="spoiler">
<p><strong>Q1:</strong> Why can't a redstone-triggered camera stand with a dimension filter photograph the target dimension?</p>
<p><strong>A1:</strong> Because that would require solving various problems, especially shader-related ones. It is not planned for now; currently it can only photograph the original dimension.</p>

<p><strong>Q2:</strong> Why does a redstone-triggered camera stand briefly flicker after installing Sable/Veil?</p>
<p><strong>A2:</strong> This is an Exposure issue, not mine.</p>

<p><strong>Q3:</strong> How is compatibility?</p>
<p><strong>A3:</strong> So far, no errors have been found when installed in the ATM10 modpack.</p>

<p><strong>Q4:</strong> Other players keep right-clicking the camera on a stand with a dimension filter and dimension film to photograph me and teleport me to other dimensions.</p>
<p><strong>A4:</strong> Please disable stand camera teleportation in the client configuration. Set <code>camera_stand.accept_stand_dimension_film_teleport</code> to <code>false</code>.</p>
</div>

## Credits and licensing

<div class="spoiler">
<p>The dimension runtime is ported and modified from Immersive Portals for NeoForge, version <strong>v6.0.7</strong>, commit <code>aede93a4865fe4aab5dd2781fb38ab3e5cecd63b</code>, by qouteall, Nick1st and upstream contributors.</p>
<p>Shutter Shadow changes package names, networking, synchronization and rendering, removes portal features, and retains the runtime needed for dimension cameras and seamless teleportation.</p>
<p>Original Shutter Shadow code is <strong>All Rights Reserved</strong>. Ported and derived upstream code remains under <strong>Apache-2.0</strong> and is not restricted by the All Rights Reserved statement for original code.</p>
<p>See the included <code>NOTICE-immersive-portals.txt</code> for attribution and modification notes.</p>
</div>

---

# 中文

Exposure 模组的扩展。通过相机观察其他维度、拍摄当前取景画面，并使用维度胶卷无缝传送玩家或带回生物。

文档：[API 与数据包扩展](wiki/API.md) · [代码职责与调用流程](wiki/CODE.md)。

## 玩法与功能

<p>将维度滤镜放入 Exposure 相机的滤镜槽，即可通过手持相机或手动操作的支架相机观察目标维度。观察期间玩家仍在原维度；安装维度胶卷并拍摄，才会触发对应传送。</p>

<table>
<thead><tr><th>附件或功能</th><th>效果</th></tr></thead>
<tbody>
<tr><td>维度滤镜</td><td>所有变体共用一个物品 ID：<code>shuttershadow:dimension_filter</code>，通过物品组件指定目标维度。默认提供主世界、下界和末地滤镜，可用数据包扩展。</td></tr>
<tr><td>玩家维度胶卷</td><td>装入维度滤镜和玩家维度胶卷手持自拍传送自己到目标维度；支架拍摄传送原维度中位于搜索范围、符合镜头条件且同意支架传送的入镜玩家。</td></tr>
<tr><td>生物维度胶卷</td><td>在目标维度搜索镜头内未被遮挡的非玩家生物，将首个符合条件的生物传送到相机所在维度。</td></tr>
<tr><td>附件提示框</td><td>鼠标悬停相机时显示附件槽，默认包括胶卷、闪光灯、镜头和滤镜。</td></tr>
</tbody>
</table>

<p>本模组创造物品栏提供已注册的滤镜变体、两种维度胶卷及各级相机附魔书。</p>

<p>手持和手动支架的照片采用玩家当前看到的相机画面，不等待整个取景范围的区块全部加载。红石触发带维度滤镜的支架时，拍摄的是支架所在维度，照片画面和元数据排除玩家，然后完成维度胶卷传送；它不拍摄目标维度。没有维度滤镜的普通支架拍摄原维度，保留 Exposure 原生玩家元数据识别。</p>

<p>玩家跨维度采用无缝传送，不额外等待目的地周围 3×3 区块。相机传送、API 和 <code>/tps</code> 都只移动选中的主体，先解除其骑乘和乘客关系；原载具与其他乘客留在原地，不随行。其他模组若阻止解除关系，本次传送会被拒绝。无缝传送不会自动寻找安全落点，也不保证未生成的目的地立即显示完整地形。</p>

## 相机附魔

<div class="spoiler">
<p>相机附魔通过铁砧应用，可以同时存在，不在附魔台或原版村民交易中获得：</p>
<table>
<thead><tr><th>附魔</th><th>等级与效果</th><th>生存获取方式</th></tr></thead>
<tbody>
<tr><td>曝光失效-相机</td><td>I，诅咒。拍摄不生成或上传图片、不增加胶卷帧数，保留快门、本模组拍摄与整帧事件、Exposure 统计和进度，以及维度胶卷传送。手持、手动支架和红石支架均有效；仍需装入可拍摄的胶卷。</td><td>下界要塞宝箱。</td></tr>
<tr><td>自恋狂-相机</td><td>I，诅咒。每次打开手持相机时默认进入自拍，可手动切回远景；支架没有自拍功能，不受影响。</td><td>下界要塞宝箱。</td></tr>
<tr><td>安全传送维度-相机</td><td>I～III，正面附魔。相机拍摄成功换维后，按相机等级为被传送玩家给予 <strong>10／20／30 秒</strong>的缓降及安全传送效果。手持、手动支架、红石支架均适用。</td><td>末地船宝箱，普通末地城宝箱不追加。</td></tr>
</tbody>
</table>
<p>符合条件的宝箱有 <strong>30%</strong> 概率额外生成一本书，原宝箱内容保留；下界要塞的两种诅咒等概率，末地船的安全传送 I～III 等概率。诅咒不能用砂轮清除。</p>
<p>安全传送效果免疫没有直接或间接实体来源的伤害，例如虚空、火、岩浆、窒息、中毒和凋零持续伤害；实体攻击、投射物及 <code>/kill</code> 不受保护。它不会熄火、搬离虚空或改变落点。普通拍照、传送失败、同维移动，以及 <code>/tps</code> 不会自动触发此附魔。</p>
</div>

## 无缝传送指令

<div class="spoiler">
<pre><code>/tps &lt;目标实体&gt; &lt;目标维度&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;
/tps @s minecraft:the_end 0 80 0
/tps @e[type=minecraft:pig,distance=..8] minecraft:overworld 0 80 0</code></pre>
</div>

## 安装与兼容

<div class="spoiler">
<p>客户端与服务端均须安装 Shutter Shadow、Exposure <strong>1.9.19</strong> 或更新版本，后续版本兼容性仍需核实。</p>
<p>当前 NeoForge 网络协议标识为 <strong>16</strong>。双方需要匹配必需通道和协议标识，由 NeoForge 协商判断兼容性。</p>
<table>
<thead><tr><th>模组</th><th>当前开发检查版本</th><th>兼容说明</th></tr></thead>
<tbody>
<tr><td>Sodium</td><td>0.8.13，NeoForge 1.21.1</td><td>可选客户端模组，已适配其区块渲染上下文；不是必需依赖。</td></tr>
<tr><td>Iris</td><td>1.8.14 Beta 1，NeoForge 1.21.1</td><td>可选客户端模组，相机沿用玩家当前光影设置。跨维度显示效果受光影包影响。</td></tr>
<tr><td>Create</td><td>6.0.10，Minecraft 1.21.1</td><td>兼容。</td></tr>
<tr><td>Sable</td><td>2.0.6，NeoForge 1.21.1</td><td>兼容。</td></tr>
<tr><td>Immersive Portals</td><td>不允许同时安装</td><td>不兼容。</td></tr>
</tbody>
</table>
<p>安装 Sodium 或 Iris 时，当前声明的最低版本分别为 <strong>0.8.13</strong> 和 <strong>1.8.14 Beta 1</strong>。原版渲染与可选模组采用各自的渲染路径；开发检查版本不代表对任意后续版本或所有光影包都已验证。</p>
<p>原版相机雾按相机绘制距离调整。光影包可能自行计算雾气，因此在游戏视距较大、相机范围较小时，地形边缘仍可能明显；本模组不修改具体光影包的雾算法。</p>
</div>

## 配置

<div class="spoiler">
<p>游戏内可从模组列表进入 Shutter Shadow 的原生配置界面。配置分为服务端玩法与加载、客户端渲染与个人偏好、本地日志三类。</p>
<table>
<thead><tr><th>文件</th><th>用途</th></tr></thead>
<tbody>
<tr><td><code>config/shuttershadow-server.toml</code></td><td>服务端玩法和相机区块加载设置，由服务器同步给客户端；世界已有 <code>serverconfig/shuttershadow-server.toml</code> 时采用世界配置。</td></tr>
<tr><td><code>config/shuttershadow-client.toml</code></td><td>客户端渲染、游戏内警告和个人传送偏好。只有支架传送同意偏好需要上报服务器。</td></tr>
<tr><td><code>config/shuttershadow-core.toml</code></td><td>通用配置，仅保留本地日志总开关；客户端与服务端分别设置，不由服务器同步。</td></tr>
</tbody>
</table>
<p><strong>服务端配置</strong>：</p>
<table>
<thead><tr><th>完整键</th><th>默认值</th><th>范围与作用</th></tr></thead>
<tbody>
<tr><td><code>dimension_camera.max_view_distance</code></td><td><code>8</code></td><td><code>3～32</code> 区块半径，限制手持和手动支架的目标维度区块订阅及绘制。实际距离还受客户端请求和服务器视距限制。</td></tr>
<tr><td><code>camera_stand.stand_player_radius</code></td><td><code>8</code></td><td><code>1～64</code> 方块，支架玩家维度胶卷搜索原维度玩家的范围，仍需符合镜头条件及个人同意偏好。</td></tr>
<tr><td><code>mob_dimension_film.capture_radius</code></td><td><code>16</code></td><td><code>1～32</code> 方块，沿 X/Y/Z 扩展目标相机方块的生物搜索盒，随后检查镜头和遮挡；不随相机视距缩小。</td></tr>
<tr><td><code>core.serverSideNormalChunkLoading</code></td><td><code>true</code></td><td>选择额外区块票据的活跃等级：开启时更新实体与方块，关闭时仅要求方块更新，生物可能停止更新；不会取消票据。</td></tr>
<tr><td><code>core.enableRemoteChunkLoading</code></td><td><code>true</code></td><td>是否主动加载相机额外订阅的远维度区块。关闭后只能使用被其他原因加载的区块，画面可能缺少地形。</td></tr>
<tr><td><code>core.delayUnloadGenerations</code></td><td><code>4</code></td><td><code>1～120</code> 个订阅更新代数，每代约 13 游戏刻；超过设定代数后释放不再观察的区块。<strong>不建议修改</strong>：过小容易反复加载，过大增加内存占用；大量区块及断线等清理仍可提前执行。</td></tr>
</tbody>
</table>
<p>相机视距配置控制的是<strong>目标维度加载订阅和绘制上限</strong>，不修改玩家普通世界的视频视距或原版模拟距离。半径 <code>8</code> 对应最多 <code>17×17</code> 区块的订阅窗口，实际订阅和当帧画出的范围还受其他限制。生物搜索以方块为单位，可跨越相机中心区块边界，不会被截成中心区块；仍须满足镜头条件。生物获取可能等待必需的扫描区块，玩家传送没有额外区块等待。</p>
<p><strong>客户端配置</strong>：</p>
<table>
<thead><tr><th>完整键</th><th>默认值</th><th>作用</th></tr></thead>
<tbody>
<tr><td><code>camera_stand.accept_stand_dimension_film_teleport</code></td><td><code>true</code></td><td>是否接受支架玩家维度胶卷传送。关闭后仍可观察和拍照，不影响 <code>/tps</code>。</td></tr>
<tr><td><code>core.enableClientPerformanceAdjustment</code></td><td><code>true</code></td><td>根据近期帧率和可用内存缩短原版目标地形绘制距离，不修改普通世界视频设置；Sodium 自身地形流程不保证受此项控制。</td></tr>
<tr><td><code>core.doCheckGlError</code></td><td><code>false</code></td><td>额外检查 OpenGL 错误，用于诊断；增加检查开销，输出服从日志总开关。</td></tr>
<tr><td><code>core.saveMemoryInBufferPack</code></td><td><code>false</code></td><td>减小本模组远维度原版区块网格缓冲的初始分配，容量不足仍增长；已有缓冲不缩小，Sodium 自有缓冲不受控制，修改后建议重启客户端。</td></tr>
<tr><td><code>core.enableWarning</code></td><td><code>true</code></td><td>统一开启或关闭全部内核游戏内提醒，包括内存和 Iris 提醒；与日志开关独立。</td></tr>
</tbody>
</table>
<p><strong>本地日志：</strong><code>config/shuttershadow-core.toml</code> 中的 <code>enableLogging</code> 默认为 <code>false</code>，只控制本机客户端或服务器的模组日志，与游戏内警告和相机事件聊天消息独立。</p>
</div>

## 数据包与滤镜

<div class="spoiler">
<p>维度滤镜使用 Exposure 的滤镜定义格式，数据文件放在：</p>
<pre><code>data/shuttershadow/dimension_filter/&lt;目标维度命名空间&gt;/&lt;目标维度路径&gt;.json</code></pre>
<p>例如 <code>data/shuttershadow/dimension_filter/minecraft/overworld.json</code>：</p>
<pre><code>{
  "predicate": {
    "items": "shuttershadow:dimension_filter",
    "components": {
      "shuttershadow:dimension_filter_target": "minecraft:overworld"
    }
  },
  "attachment_texture": "exposure:textures/gui/filter/stained_glass.png",
  "attachment_tint": "78A7FF",
  "shader": "shuttershadow:shaders/post/neutral.json"
}</code></pre>
<p><code>shuttershadow:dimension_filter_target</code> 指定目标维度。只写以上结构即可从其他维度观察主世界，无需来源路由或距离比例字段；目标维度必须在服务器实际存在，同维度不建立跨维度观察。</p>
<p>滤镜物品模型采用相同的维度路径，放在资源包中：</p>
<pre><code>assets/shuttershadow/models/item/dimension_filter/&lt;目标维度命名空间&gt;/&lt;目标维度路径&gt;.json</code></pre>
<p>自定义纹理、模型和译名需要相应资源包。新增符合上述物品和目标组件谓词的滤镜定义后，创造物品栏会自动列出其目标变体。数据包定义、可选来源路由和 Java 扩展契约详见 <a href="wiki/API.md">API Wiki</a>。</p>
</div>

## 扩展 API 与维护

<p>公开 API 提供单主体无缝传送、额外区块订阅和维度滤镜工具。相机事件发布到 NeoForge 游戏事件总线，允许扩展设置打开模式和观察场景，在拍摄前修改计划，选择照片或传送对象，修改帧数据及客户端图片，控制单个对象的传送，并读取真实完成结果。默认玩家胶卷和生物胶卷仍由内部事务管理器协调换维、截图与上传。</p>
<p>当前拍摄流程已将 Exposure 的三种元数据／帧事件替换为本模组事件，也包括未安装维度附件的普通相机；依赖这些 Exposure 事件的扩展需要接入新 API。Exposure 原生元数据生成、统计、进度和实体被拍行为继续执行。曝光失效采用 <code>NO_IMAGE</code> 计划：仍有逻辑拍摄、整帧事件及胶卷传送，但不会出现图片就绪事件。</p>
<p>当前源码保留已启用的游戏内事件测试监听器 <code>CameraEventTest</code>，向相关玩家的聊天栏显示打开、观察、拍摄及传送阶段。它由 <code>CameraEventTest.ENABLED</code> 控制，与日志总开关和游戏内警告开关独立。类职责、资源所有权和调用流程见 <a href="wiki/CODE.md">代码 Wiki</a>。</p>

## Q&A

<div class="spoiler">
<p><strong>Q1：</strong>为什么红石触发的带有维度滤镜的支架照相机拍不到目标维度的画面？</p>
<p><strong>A1：</strong>因为这需要解决各种问题，尤其是光影，暂不考虑加入，目前只能拍到原维度。</p>

<p><strong>Q2：</strong>为什么装了 Sable/Veil 模组后红石触发支架照相机出现短暂闪烁？</p>
<p><strong>A2：</strong>这是 Exposure 的问题，不是我的。</p>

<p><strong>Q3：</strong>兼容性如何？</p>
<p><strong>A3：</strong>目前安装在 ATM10 整合包并未发现报错。</p>

<p><strong>Q4：</strong>其它玩家总是右键支架上的带有维度滤镜和维度胶卷的照相机拍摄我，将我传送到其它维度。</p>
<p><strong>A4：</strong>请在客户端配置中关闭支架照相机传送，将 <code>camera_stand.accept_stand_dimension_film_teleport</code> 设为 <code>false</code>。</p>
</div>

## 上游与许可

<div class="spoiler">
<p>跨维度内核移植并修改自 Immersive Portals for NeoForge，版本 <strong>v6.0.7</strong>，提交 <code>aede93a4865fe4aab5dd2781fb38ab3e5cecd63b</code>，原作者为 qouteall、Nick1st 及上游贡献者。本项目调整了包名、网络、同步与渲染实现，删除传送门功能，保留相机跨维度观察和无缝传送所需内核。</p>
<p>本模组原创部分采用 <strong>All Rights Reserved</strong>；上游移植及衍生部分继续遵循 <strong>Apache-2.0</strong>，不受原创部分的 All Rights Reserved 声明限制。上游来源和修改说明见模组内 <code>NOTICE-immersive-portals.txt</code>。</p>
</div>

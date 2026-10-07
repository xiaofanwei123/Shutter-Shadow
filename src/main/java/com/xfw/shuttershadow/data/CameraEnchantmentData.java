package com.xfw.shuttershadow.data;

import com.google.gson.JsonParser;
import com.xfw.shuttershadow.CameraEnchantments;
import com.xfw.shuttershadow.Shuttershadow;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.EnchantmentTagsProvider;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.common.data.LanguageProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 数据生成入口，不在游戏运行时生成配置资源。 */
@EventBusSubscriber(modid = Shuttershadow.MODID)
public final class CameraEnchantmentData {
    /** includeServer时生成附魔registry JSON和tags，includeClient时生成en_us/zh_cn。 */
    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        if (event.includeServer()) {
            event.createDatapackRegistryObjects(
                    new RegistrySetBuilder().add(Registries.ENCHANTMENT, CameraEnchantmentData::bootstrap));
            event.createProvider((output, lookup) -> new Tags(output, lookup, event.getExistingFileHelper()));
        }
        if (event.includeClient()) {
            event.createProvider(output -> new Languages(output, "en_us"));
            event.createProvider(output -> new Languages(output, "zh_cn"));
        }
    }

    /** 以Exposure camera作为支持物品集合，注册两种等级1的负面附魔定义。 */
    private static void bootstrap(BootstrapContext<Enchantment> context) {
        HolderSet<Item> cameras = HolderSet.direct(context.lookup(Registries.ITEM).getOrThrow(
                ResourceKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("exposure", "camera"))));
        for (ResourceKey<Enchantment> key : List.of(CameraEnchantments.EXPOSURE_FAILURE, CameraEnchantments.NARCISSISM)) {
            context.register(key, Enchantment.enchantment(Enchantment.definition(
                    cameras, 1, 1, Enchantment.constantCost(25), Enchantment.constantCost(50),
                    8, EquipmentSlotGroup.ANY)).build(key.location()));
        }
    }

    /** 两附魔标签provider。 */
    private static final class Tags extends EnchantmentTagsProvider {
        /** 传output、lookup、本命名空间及existing file helper给父类。 */
        private Tags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper files) {
            super(output, lookup, Shuttershadow.MODID, files);
        }

        /** 把两附魔加入curse、treasure、tradeable及tooltip_order。 */
        @Override
        protected void addTags(HolderLookup.Provider provider) {
            // 村民出售附魔书；诅咒可在铁砧附上，也不会被砂轮洗掉。
            for (var tag : List.of(EnchantmentTags.CURSE, EnchantmentTags.TREASURE,
                    EnchantmentTags.TRADEABLE, EnchantmentTags.TOOLTIP_ORDER)) {
                tag(tag).add(CameraEnchantments.EXPOSURE_FAILURE, CameraEnchantments.NARCISSISM);
            }
        }
    }

    /** 中英语言provider，保留非本次维护的旧键，重新生成附魔/配置/命令键。 */
    private static final class Languages extends LanguageProvider {
        private final Path source;
        private final boolean chinese;

        /** 将PackOutput重定向到main/resources，保存语言源JSON路径及是否中文标记。 */
        private Languages(PackOutput output, String locale) {
            super(new PackOutput(output.getOutputFolder().resolve("../../main/resources").normalize()),
                    Shuttershadow.MODID, locale);
            source = output.getOutputFolder().resolve("../../main/resources/assets/shuttershadow/lang/" + locale + ".json").normalize();
            chinese = locale.equals("zh_cn");
        }

        /** 读现有语言保留其他键，重写两附魔、所有配置标题/tooltip和tps反馈。 */
        @Override
        protected void addTranslations() {
            String failure = "enchantment.shuttershadow.exposure_failure";
            String narcissism = "enchantment.shuttershadow.narcissism";
            try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                for (var entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    if (!entry.getKey().equals(failure) && !entry.getKey().equals(narcissism)
                            && !entry.getKey().equals("shuttershadow.core.warning_config_hint")
                            && !entry.getKey().startsWith("shuttershadow.configuration.")
                            && !entry.getKey().startsWith("commands.shuttershadow.tps.")) {
                        add(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Cannot read existing camera translations: " + source, exception);
            }
            add(failure, chinese ? "曝光失效" : "Exposure Failure");
            add(narcissism, chinese ? "自恋狂" : "Narcissism");
            add("shuttershadow.configuration.title", chinese ? "%s 配置" : "%s Configuration");
            addConfigOption("dimension_camera", "维度相机", "Dimension Camera",
                    "服务端统一限制手持和手动支架相机的目标维度取景距离。单位为区块，与生物获取范围的方块单位不同。",
                    "Server limits for target-dimension views from handheld and manually operated stand cameras. View distance is measured in chunks; creature search range is measured in blocks.");
            addConfigOption("camera_stand", "照相机支架", "Camera Stand",
                    "服务端配置控制支架出镜玩家的搜索范围；客户端配置控制你是否接受支架玩家维度胶卷传送。范围均以方块计。",
                    "Server settings control the search range for players in a stand camera's frame. Client settings control whether you accept stand player dimension-film teleportation. Search range is measured in blocks.");
            addConfigOption("mob_dimension_film", "生物维度胶卷", "Creature Dimension Film",
                    "控制目标维度中可被生物维度胶卷获取的实体搜索范围。范围内的实体仍需满足镜头视锥、焦距和遮挡判定。",
                    "Controls the target-dimension entity search range for creature dimension film. Entities must also pass the lens frustum, focal-length and obstruction checks.");
            addConfigOption("max_remote_view_distance", "目标维度最大取景距离（区块）", "Maximum Target Dimension View Distance (Chunks)",
                    "单位为区块半径，默认 8，范围 3～32。限制手持和手动支架的目标维度加载与绘制距离，实际值还取决于客户端请求和服务器视距。半径 3 的订阅窗口最多为 7×7 区块，半径 8 最多为 17×17。性能调整可进一步缩小绘制距离；生物搜索范围仍以方块计，不由此项裁剪。",
                    "Measured as a chunk radius. Default: 8; range: 3-32. Caps remote loading and rendering for handheld and manual stand cameras, also limited by client requests and server view distance. Radius 3 subscribes to at most 7x7 chunks; radius 8 to at most 17x17. Performance adjustment may further reduce rendering distance. Creature search range is measured separately in blocks and is not cropped by this setting.");
            addConfigOption("stand_player_radius", "支架出镜玩家范围", "Stand players in frame range",
                    "单位为方块，默认 8，范围 1～64。以支架相机为中心搜索可能出镜的玩家，再由镜头视锥和遮挡判定筛选。适用于手动与红石支架的玩家维度胶卷传送，不决定目标维度取景视距。",
                    "Measured in blocks. Default: 8; range: 1-64. Searches for players around the stand, then applies lens frustum and obstruction checks. Applies to player dimension-film transfers from manual and redstone stands. Does not set target-dimension view distance.");
            addConfigOption("mob_capture_radius", "生物获取范围", "Creature search range",
                    "单位为方块，默认 16，范围 1～32。目标维度相机所在方块的搜索盒沿 X、Y、Z 各方向扩展指定格数，再筛选镜头内未被遮挡的生物。手持、手动支架和红石支架共用此值；它不随取景视距缩小，调低可减少生物搜索负担。",
                    "Measured in blocks. Default: 16; range: 1-32. Expands the target camera block's search box by this amount along X, Y and Z, then selects unobstructed creatures within the lens view. Shared by handheld, manual stand and redstone stand captures. Independent of view distance; lower values reduce entity search work.");
            addConfigOption("accept_stand_dimension_film_teleport", "接受支架玩家维度胶卷传送", "Accept stand player dimension-film teleport",
                    "个人客户端偏好，默认开启，连接服务器时会同步。开启后，装有玩家维度胶卷的支架相机可在你出镜时传送你；关闭后拒绝这类传送，目标维度观察、拍摄和玩家投影仍可使用。",
                    "Personal client preference, enabled by default and sent to the server while connected. Allows player dimension-film stands to teleport you when you are in frame. Disabling refuses these transfers while preserving remote viewing, photos and player projections.");
            add("commands.shuttershadow.tps.success", chinese
                    ? "已将 %s 个实体无缝传送至 %s（%s，%s，%s）。"
                    : "Seamlessly teleported %s entities to %s (%s, %s, %s).");
            add("commands.shuttershadow.tps.refused", chinese
                    ? "有 %s 个实体未能传送；成功 %s 个。"
                    : "%s entities could not be teleported; %s succeeded.");
            add("shuttershadow.core.warning_config_hint", chinese
                    ? "如需关闭所有内核游戏内警告，请在 config/shuttershadow-client.toml 的 [core] 中将 enableWarning 设为 false。"
                    : "To hide all in-game runtime warnings, set enableWarning to false under [core] in config/shuttershadow-client.toml.");
            addConfigOption("core", "维度内核", "Dimension Runtime",
                    "客户端配置控制相机渲染、缓冲内存与游戏内警告；服务端配置控制相机远维度区块加载、票据等级和卸载延迟。",
                    "Client settings control camera rendering, buffer memory and in-game warnings. Server settings control remote camera chunk loading, ticket activity and unload delay.");
            addConfigOption("core.enableClientPerformanceAdjustment", "客户端性能调整", "Client Performance Adjustment",
                    "原版地形渲染根据近期平均帧率和可用内存缩短相机目标维度的绘制距离，每 5 秒评估一次，不扩大服务端上限。关闭后不再自动缩短，不修改视频设置或普通世界视距。Sodium 使用自身地形渲染流程，此项不保证对其生效。",
                    "Reduces target-dimension distance in vanilla terrain rendering using recent average frame rate and available memory, evaluated every 5 seconds without exceeding the server cap. Disabling removes automatic reduction and does not change video settings or normal world view distance. Sodium uses its own terrain renderer; this setting is not guaranteed to affect it.");
            addConfigOption("core.doCheckGlError", "检查 OpenGL 错误", "Check OpenGL Errors",
                    "在渲染流程中额外检查 OpenGL 错误，用于排查黑屏、渲染异常等问题，日志输出服从本模组日志总开关。默认关闭；开启会增加检查开销，不会直接修复渲染错误。",
                    "Adds OpenGL error checks during rendering for diagnosing black screens or rendering faults. Log output follows this mod's master logging toggle. Disabled by default. Adds checking overhead; it does not repair rendering errors.");
            addConfigOption("core.saveMemoryInBufferPack", "减小区块网格缓冲内存", "Reduce Chunk Mesh Buffer Memory",
                    "减小原版新建区块网格缓冲区的初始分配，需要更多空间时仍会自动增长，可降低多维度初始缓冲占用，不改变网格精度。已有缓冲不会缩小，修改后建议重启客户端；Sodium 自身的缓冲不由此项控制。",
                    "Reduces initial allocation of newly created vanilla chunk mesh buffers, which still grow as needed. Can reduce initial memory for multiple dimensions without changing mesh detail. Existing buffers are not shrunk; restart the client after changing. Sodium's own buffers are not controlled by this setting.");
            addConfigOption("core.enableWarning", "显示所有内核警告", "Show All Runtime Warnings",
                    "统一开启或关闭所有内核游戏内警告，包括内存和 Iris 提醒，默认开启。关闭后不再显示这些提醒，不改变内存检测或日志总开关；已经显示的聊天消息不会被清除。",
                    "Enables or disables all in-game runtime warnings, including memory and Iris notices. Enabled by default. Disabling hides these notices without changing memory detection or the master logging toggle; existing chat messages are not removed.");
            addConfigOption("core.enableLogging", "输出所有模组日志", "Log All Mod Messages",
                    "统一控制本模组的控制台和日志文件输出，默认关闭。开启后记录故障诊断、警告和错误，并遵守日志级别。关闭时也不记录本模组的警告和错误，不影响 Minecraft 或其他模组的日志；游戏内提醒由内核警告开关单独控制。",
                    "Controls this mod's console and file logs, disabled by default. Enabling records failure diagnostics, warnings and errors while respecting log levels. Disabling also suppresses this mod's warnings and errors without affecting Minecraft or other mods. In-game notices are controlled separately by the runtime warning toggle.");
            addConfigOption("core.serverSideNormalChunkLoading", "维持相机区块加载票据", "Keep Camera Chunk Tickets Active",
                    "服务端选择相机额外区块加载票据的活跃等级。默认开启，目标区块可更新实体与方块；关闭后票据仅要求方块更新，生物可能停止更新。关闭不取消订阅或加载票据。更改前建议退出世界，更改后重新进入，使新票据统一使用新等级。",
                    "Selects the activity level of additional server camera chunk tickets. Enabled by default for entity and block ticking. Disabling only requires block ticking, so creatures may stop updating; subscriptions and loading tickets remain. Leave the world before changing, then re-enter so new tickets consistently use the new level.");
            addConfigOption("core.enableRemoteChunkLoading", "启用相机远维度区块加载", "Enable Remote Camera Chunk Loading",
                    "服务端是否为相机额外订阅的区块添加加载票据，默认开启。关闭后，相机不再主动加载这些远维度区块，只能使用已被其他原因加载的区块，因此观察画面可能缺少地形。不会关闭原版玩家区块加载。",
                    "Controls whether the server adds loading tickets for additional camera chunks. Enabled by default. Disabling stops active loading of those remote chunks; camera views depend on chunks loaded for other reasons and may lack terrain. Normal player chunk loading is unaffected.");
            addConfigOption("core.delayUnloadGenerations", "区块卸载延迟（§c不建议修改§r）", "Chunk Unload Delay (§cChange Not Recommended§r)",
                    "服务端配置，默认 4，范围 1～120。单位为订阅更新代数，每代 13 游戏刻；超过设定代数才清理不再观察的相机区块，并非精确的秒数。值越大保留区块越久、内存占用越高，过小可能导致反复加载。额外加载超过 1200 或 2000 个区块时，延迟分别缩短至最多 2 或 1 代。重生、断线等强制清理不受此项影响。",
                    "Server setting. Default: 4; range: 1-120. Measured in subscription generations, each spanning 13 game ticks. Unwatched camera chunks are cleared only after this threshold is exceeded, so this is not an exact duration in seconds. Higher values retain chunks longer and use more memory; low values may cause repeated loading. More than 1200 or 2000 additional chunks caps the delay at 2 or 1 generations respectively. Forced cleanup on respawn or disconnect is unaffected.");
            add("shuttershadow.configuration.core.delayUnloadGenerations.tooltip.warning", chinese
                    ? "不建议修改：默认值用于平衡相机切换时的区块复用和内存释放。"
                    : "Changing this is not recommended: the default balances chunk reuse while switching cameras against memory release.");
        }

        /** 给单配置项生成shuttershadow.configuration.<key>及其.tooltip中英文。 */
        private void addConfigOption(String key, String chineseName, String englishName,
                                     String chineseTooltip, String englishTooltip) {
            String translationKey = "shuttershadow.configuration." + key;
            add(translationKey, chinese ? chineseName : englishName);
            add(translationKey + ".tooltip", chinese ? chineseTooltip : englishTooltip);
        }
    }
}

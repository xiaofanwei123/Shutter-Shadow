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
                    "单位为方块，默认 16，范围 1～16。目标维度相机所在方块的搜索盒沿 X、Y、Z 各方向扩展指定格数，再筛选镜头内未被遮挡的生物。手持、手动支架和红石支架共用此值；它不随取景视距缩小，调低可减少生物搜索负担。",
                    "Measured in blocks. Default: 16; range: 1-16. Expands the target camera block's search box by this amount along X, Y and Z, then selects unobstructed creatures within the lens view. Shared by handheld, manual stand and redstone stand captures. Independent of view distance; lower values reduce entity search work.");
            addConfigOption("accept_stand_dimension_film_teleport", "接受支架玩家维度胶卷传送", "Accept stand player dimension-film teleport",
                    "个人客户端偏好，默认开启，连接服务器时会同步。开启后，装有玩家维度胶卷的支架相机可在你出镜时传送你；关闭后拒绝这类传送，目标维度观察、拍摄和玩家投影仍可使用。",
                    "Personal client preference, enabled by default and sent to the server while connected. Allows player dimension-film stands to teleport you when you are in frame. Disabling refuses these transfers while preserving remote viewing, photos and player projections.");
            add("commands.shuttershadow.tps.success", chinese
                    ? "已将 %s 个实体无缝传送至 %s（%s，%s，%s）。"
                    : "Seamlessly teleported %s entities to %s (%s, %s, %s).");
            add("commands.shuttershadow.tps.refused", chinese
                    ? "有 %s 个实体未能传送；成功 %s 个。"
                    : "%s entities could not be teleported; %s succeeded.");
            addConfigOption("core.enableClientPerformanceAdjustment", "客户端性能调整", "Client Performance Adjustment",
                    "原版地形渲染根据近期平均帧率和可用内存缩短相机目标维度的绘制距离，每 5 秒评估一次，不扩大服务端上限。关闭后不再自动缩短，不修改视频设置或普通世界视距。Sodium 使用自身地形渲染流程，此项不保证对其生效。",
                    "Reduces target-dimension distance in vanilla terrain rendering using recent average frame rate and available memory, evaluated every 5 seconds without exceeding the server cap. Disabling removes automatic reduction and does not change video settings or normal world view distance. Sodium uses its own terrain renderer; this setting is not guaranteed to affect it.");
            addConfigOption("core.clientTolerantVersionMismatchWithServer", "允许服务端协议版本差异", "Allow Server Protocol Version Mismatch",
                    "客户端连接时允许忽略本模组内核握手中的主、次协议版本差异，默认关闭。此项不会转换数据包，也不会让不同格式的网络协议自动兼容。只有确认协议兼容时才开启，修改后重新连接生效。",
                    "Allows the client to tolerate major or minor protocol differences in this mod's runtime handshake. Disabled by default. Does not convert packets or make incompatible formats work together. Enable only for confirmed compatible protocols; reconnect after changing.");
            addConfigOption("core.doCheckGlError", "检查 OpenGL 错误", "Check OpenGL Errors",
                    "在渲染流程中额外检查 OpenGL 错误并写入日志，用于排查黑屏、渲染异常等问题。默认关闭；开启会增加检查和日志开销，不会直接修复渲染错误。",
                    "Adds OpenGL error checks during rendering and records errors in the log for diagnosing black screens or rendering faults. Disabled by default. Adds checking and logging overhead; it does not repair rendering errors.");
            addConfigOption("core.saveMemoryInBufferPack", "减小区块网格缓冲内存", "Reduce Chunk Mesh Buffer Memory",
                    "减小原版新建区块网格缓冲区的初始分配，需要更多空间时仍会自动增长，可降低多维度初始缓冲占用，不改变网格精度。已有缓冲不会缩小，修改后建议重启客户端；Sodium 自身的缓冲不由此项控制。",
                    "Reduces initial allocation of newly created vanilla chunk mesh buffers, which still grow as needed. Can reduce initial memory for multiple dimensions without changing mesh detail. Existing buffers are not shrunk; restart the client after changing. Sodium's own buffers are not controlled by this setting.");
            addConfigOption("core.enableWarning", "显示内核警告", "Show Runtime Warnings",
                    "控制本模组在游戏内显示的内存不足、Iris 和显卡提醒，默认开启。关闭后隐藏这些提醒，内存检测与诊断日志仍保留。只想屏蔽某一类提醒时，使用下方的警告 ID 列表。",
                    "Controls this mod's in-game memory, Iris and graphics-card notices. Enabled by default. Disabling hides these notices while retaining memory detection and diagnostic logs. Use the warning ID list below to hide individual notice types.");
            addConfigOption("core.serverSideNormalChunkLoading", "维持相机区块加载票据", "Keep Camera Chunk Tickets Active",
                    "服务端选择相机额外区块加载票据的活跃等级。默认开启，目标区块可更新实体与方块；关闭后票据仅要求方块更新，生物可能停止更新。关闭不取消订阅或加载票据。更改前建议退出世界，更改后重新进入，使新票据统一使用新等级。",
                    "Selects the activity level of additional server camera chunk tickets. Enabled by default for entity and block ticking. Disabling only requires block ticking, so creatures may stop updating; subscriptions and loading tickets remain. Leave the world before changing, then re-enter so new tickets consistently use the new level.");
            addConfigOption("core.chunkPacketDebug", "记录区块数据包调试日志", "Log Chunk Packet Diagnostics",
                    "在客户端日志中记录跨维度区块数据包的载入和卸载信息，用于排查多维度同步。默认关闭；高视距下可能产生大量日志，增加日志写入开销。只记录信息，不改变区块同步流程。",
                    "Logs client-side dimension chunk packet loads and unloads for diagnosing synchronization. Disabled by default. Large view distances can produce many entries and increase logging overhead. Records diagnostics without changing chunk synchronization.");
            addConfigOption("core.enableRemoteChunkLoading", "启用相机远维度区块加载", "Enable Remote Camera Chunk Loading",
                    "服务端是否为相机额外订阅的区块添加加载票据，默认开启。关闭后，相机不再主动加载这些远维度区块，只能使用已被其他原因加载的区块，因此观察画面可能缺少地形。不会关闭原版玩家区块加载。",
                    "Controls whether the server adds loading tickets for additional camera chunks. Enabled by default. Disabling stops active loading of those remote chunks; camera views depend on chunks loaded for other reasons and may lack terrain. Normal player chunk loading is unaffected.");
            addConfigOption("core.serverTolerantVersionMismatchWithClient", "允许客户端协议版本差异", "Allow Client Protocol Version Mismatch",
                    "服务端握手时允许客户端使用不同的主、次内核协议版本，默认关闭。此项仅放宽该握手检查，不会转换数据包或修复协议差异。只有确认协议兼容时才开启，客户端重新连接后生效。",
                    "Allows the server to accept clients with different major or minor runtime protocol versions. Disabled by default. Only relaxes this handshake check; does not convert packets or resolve protocol differences. Enable only for confirmed compatible protocols; applies when clients reconnect.");
            addConfigOption("core.serverRejectClientWithoutShuttershadow", "拒绝缺少维度协议的客户端", "Reject Clients Without Dimension Networking",
                    "独立服务器额外拒绝未通过 NeoForge 握手、无法提供本模组维度协议的客户端，默认开启。关闭只略过这项额外检查，NeoForge 的必要数据包通道检查仍保留，不会让原版客户端获得跨维功能。修改后重新连接时检查。",
                    "Adds a dedicated-server rejection for clients without a NeoForge handshake or this mod's dimension protocol. Enabled by default. Disabling skips only this extra check; NeoForge's required payload channel checks remain, and vanilla clients do not gain dimension support. Checked when reconnecting.");
            addConfigOption("core.serverTeleportLogging", "记录无缝传送日志", "Log Seamless Teleports",
                    "在服务端日志中记录玩家无缝传送的维度与坐标，便于排查传送位置和同步问题。默认关闭；只影响日志输出，不改变传送条件、速度或是否等待区块。",
                    "Records player seamless-teleport dimensions and coordinates in the server log for diagnosing destinations and synchronization. Disabled by default. Only affects logging; does not change transfer conditions, speed or chunk waiting.");
            addConfigOption("core.disabledWarnings", "屏蔽的警告 ID", "Hidden Warning IDs",
                    "按 ID 屏蔽特定游戏内提醒，列表可为空。可用 ID：iris（光影）、nvidia（显卡）、low_max_memory（分配内存偏低）、memory_not_enough（运行中内存不足）、mod_version_mismatch（协议版本差异）。每个 ID 单独一项；总开关关闭时全部隐藏，已显示的聊天消息不会被清除。",
                    "Hides selected in-game notices by ID; the list may be empty. IDs: iris, nvidia, low_max_memory (low allocated memory), memory_not_enough (memory pressure), and mod_version_mismatch (protocol differences). Enter each ID separately. The master toggle hides all notices; existing chat messages are not removed.");
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

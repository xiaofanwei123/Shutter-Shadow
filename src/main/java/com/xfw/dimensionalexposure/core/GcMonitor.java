package com.xfw.dimensionalexposure.core;

import com.xfw.dimensionalexposure.DimensionalExposure;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.xfw.dimensionalexposure.util.CHelper;
import com.xfw.dimensionalexposure.core.CoreSettings;
import com.xfw.dimensionalexposure.core.CoreConfig;
import com.xfw.dimensionalexposure.core.PlatformBridge;
import com.xfw.dimensionalexposure.util.Helper;
import com.xfw.dimensionalexposure.util.CountDownInt;
import com.xfw.dimensionalexposure.util.MyTaskList;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.WeakHashMap;

// 运行时没有可直接读取垃圾回收暂停时长的接口。
// 垃圾回收日志也无法在程序内部直接获取，
// 因此这里只进行近似测量。
/** 监测垃圾回收停顿和堆内存压力。 */
public class GcMonitor {
    private static boolean memoryNotEnough = false;

    // JVM 的收集器实例固定；缓存列表，计数仍在每次更新时实时读取。
    private static final List<GarbageCollectorMXBean> COLLECTORS =
        ManagementFactory.getGarbageCollectorMXBeans();
    
    private static final WeakHashMap<GarbageCollectorMXBean, Long> lastCollectCount =
        new WeakHashMap<>();

    private static final CountDownInt MESSAGE_LIMIT = new CountDownInt(3);
    private static final CountDownInt LOG_LIMIT = new CountDownInt(3);
    
    private static long lastUpdateTime = 0;
    private static long lastLongPauseTime = 0;
    
    public static final String LINK = "https://filmora.wondershare.com/game-recording/how-to-allocate-more-ram-to-minecraft.html";
    
    // 仅供客户端使用。
    /** 注册客户端帧前采样。 */
    public static void initClient() {
        NeoForge.EVENT_BUS.addListener(CoreSettings.PreGameRenderEvent.class, preGameRenderEvent -> GcMonitor.update());
        
        long maxMemory = Runtime.getRuntime().maxMemory();
        long maxMemoryMB = Helper.toMiB(maxMemory);
        if (maxMemoryMB <= 2048) {
            CoreSettings.CLIENT_TASK_LIST.addTask(MyTaskList.withDelayCondition(
                () -> Minecraft.getInstance().level == null,
                MyTaskList.oneShotTask(() -> {
                    if (CoreConfig.shouldDisplayWarning()) {
                        CHelper.printChat(
                            Component.translatable("dimensional_exposure.core.low_max_memory", maxMemoryMB)
                                .withStyle(ChatFormatting.RED)
                                .append(CHelper.getLinkText(LINK))
                                .append(
                                    CHelper.getDisableWarningText()
                                )
                        );
                    }
                })
            ));
        }
    }
    
    /** 专用服务端游戏刻结束后采样，避免单人重复采样。 */
    public static void initCommon() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            if (event.getServer().isDedicatedServer()) {
                update();
            }
        });
    }
    
    /** 检测垃圾回收和长停顿，并检查堆内存压力。 */
    private static void update() {
        double longPauseThresholdSeconds = 0.3;
        if (Helper.toMiB(Runtime.getRuntime().maxMemory()) < 2049) {
            // 仅分配 2048 兆字节时，使用更敏感的内存判定。
            longPauseThresholdSeconds = 0.1;
        }
        
        long currTime = System.nanoTime();
        if (currTime - lastUpdateTime > Helper.secondToNano(longPauseThresholdSeconds)) {
            lastLongPauseTime = currTime;
        }
        lastUpdateTime = currTime;
        
        for (GarbageCollectorMXBean garbageCollectorMXBean : COLLECTORS) {
            long currCount = garbageCollectorMXBean.getCollectionCount();
            
            Long lastCount = lastCollectCount.get(garbageCollectorMXBean);
            lastCollectCount.put(garbageCollectorMXBean, currCount);
            
            if (lastCount != null) {
                if (lastCount != currCount) {
                    check();
                }
            }
        }
        
    }
    
    /** 结合剩余堆内存与近期停顿判断内存不足，并限制提醒频率。 */
    private static void check() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        long totalMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double timeFromLongPause = System.nanoTime() - lastLongPauseTime;
        
        if (Helper.toMiB(maxMemory - usedMemory) < 300 && timeFromLongPause < Helper.secondToNano(2)) {
            if (memoryNotEnough) {
                // 第二次检测到内存紧张时才显示消息。
                
                if (!PlatformBridge.isDedicatedServer()) {
                    informMemoryNotEnoughClient();
                }
            }
            
            if (LOG_LIMIT.tryDecrement()) {
                // 使用 ZGC 时，内存占用的下降存在延迟。
                
                DimensionalExposure.LOGGER.warn(String.format(
                    """
                    Memory seems not enough. Try to Shrink loading distance or allocate more memory.
                    Memory: % 2d%% %03d/%03dMB
                    (Note: The memory check may be inaccurate.)
                    """,
                    usedMemory * 100L / maxMemory,
                    Helper.toMiB(usedMemory), Helper.toMiB(maxMemory)
                ));

                if (LOG_LIMIT.isZero()) {
                    DimensionalExposure.LOGGER.info("Memory warning logging reached limit.");
                }
            }
            
            memoryNotEnough = true;
        }
        else {
            memoryNotEnough = false;
        }
    }
    
    // 仅供客户端使用。
    /** 配置允许时显示内存不足提醒。 */
    private static void informMemoryNotEnoughClient() {
        if (!CoreConfig.shouldDisplayWarning()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            if (client.player.tickCount > 40) {
                if (MESSAGE_LIMIT.tryDecrement()) {
                    // 仅替换这条提醒的模组前缀，保留当前语言的正文及帮助链接。
                    String message = Component.translatable("dimensional_exposure.core.memory_not_enough").getString()
                        .replaceFirst("^\\[[^\\]]*\\]", "[dimensional_exposure]");
                    CHelper.printChat(
                        Component.literal(message).append(
                            CHelper.getLinkText(LINK)
                        )
                    );
                }
            }
        }
    }
    
    /** 返回最近GC检查的内存不足状态。 */
    public static boolean isMemoryNotEnough() {
        return memoryNotEnough;
    }
}

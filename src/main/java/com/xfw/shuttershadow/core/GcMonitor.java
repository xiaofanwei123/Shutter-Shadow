package com.xfw.shuttershadow.core;
// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import com.xfw.shuttershadow.util.CHelper;
import com.xfw.shuttershadow.core.CoreSettings;
import com.xfw.shuttershadow.util.WorldContextHelper;
import com.xfw.shuttershadow.util.McHelper;
import com.xfw.shuttershadow.core.CoreConfig;
import com.xfw.shuttershadow.core.PlatformBridge;
import com.xfw.shuttershadow.util.Helper;
import com.xfw.shuttershadow.util.CountDownInt;
import com.xfw.shuttershadow.util.MyTaskList;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.WeakHashMap;

// Java does not provide a program-accessible interface to tell GC pause time.
// (There are GC logs, but not accessible from within program)
// so I can only roughly measure it.
// Shuttershadow 第三轮修改：仅将 MiB 换算迁至公共工具，保留内存监测与告警行为。
public class GcMonitor {
    private static boolean memoryNotEnough = false;

    // JVM 的收集器实例固定；缓存列表，计数仍在每次更新时实时读取。
    private static final List<GarbageCollectorMXBean> COLLECTORS =
        ManagementFactory.getGarbageCollectorMXBeans();
    
    private static final WeakHashMap<GarbageCollectorMXBean, Long> lastCollectCount =
        new WeakHashMap<>();
    
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final CountDownInt MESSAGE_LIMIT = new CountDownInt(3);
    private static final CountDownInt LOG_LIMIT = new CountDownInt(3);
    
    private static long lastUpdateTime = 0;
    private static long lastLongPauseTime = 0;
    
    public static final String LINK = "https://filmora.wondershare.com/game-recording/how-to-allocate-more-ram-to-minecraft.html";
    
    //@OnlyIn(Dist.CLIENT)
    public static void initClient() {
        NeoForge.EVENT_BUS.addListener(CoreSettings.PreGameRenderEvent.class, preGameRenderEvent -> GcMonitor.update());
        
        long maxMemory = Runtime.getRuntime().maxMemory();
        long maxMemoryMB = Helper.toMiB(maxMemory);
        if (maxMemoryMB <= 2048) {
            CoreSettings.CLIENT_TASK_LIST.addTask(MyTaskList.withDelayCondition(
                () -> Minecraft.getInstance().level == null,
                MyTaskList.oneShotTask(() -> {
                    if (CoreConfig.shouldDisplayWarning("low_max_memory")) {
                        CHelper.printChat(
                            Component.translatable("shuttershadow.core.low_max_memory", maxMemoryMB)
                                .withStyle(ChatFormatting.RED)
                                .append(McHelper.getLinkText(LINK))
                                .append(
                                    WorldContextHelper.getDisableWarningText("low_max_memory")
                                )
                        );
                    }
                })
            ));
        }
    }
    
    public static void initCommon() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            if (event.getServer().isDedicatedServer()) {
                update();
            }
        });
    }
    
    private static void update() {
        double longPauseThresholdSeconds = 0.3;
        if (Helper.toMiB(Runtime.getRuntime().maxMemory()) < 2049) {
            // if only allocated 2048 MB, be more sensitive
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
    
    private static void check() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        long totalMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double timeFromLongPause = System.nanoTime() - lastLongPauseTime;
        
        if (Helper.toMiB(maxMemory - usedMemory) < 300 && timeFromLongPause < Helper.secondToNano(2)) {
            if (memoryNotEnough) {
                // show message the second time
                
                if (!PlatformBridge.isDedicatedServer()) {
                    informMemoryNotEnoughClient();
                }
            }
            
            if (LOG_LIMIT.tryDecrement()) {
                // When using ZGC, the memory usage amount is decreased with a delay
                
                LOGGER.warn(String.format(
                    """
                    Memory seems not enough. Try to Shrink loading distance or allocate more memory.
                    Memory: % 2d%% %03d/%03dMB
                    (Note: The memory check may be inaccurate.)
                    """,
                    usedMemory * 100L / maxMemory,
                    Helper.toMiB(usedMemory), Helper.toMiB(maxMemory)
                ));

                if (LOG_LIMIT.isZero()) {
                    LOGGER.info("Memory warning logging reached limit.");
                }
            }
            
            memoryNotEnough = true;
        }
        else {
            memoryNotEnough = false;
        }
    }
    
    //@OnlyIn(Dist.CLIENT)
    private static void informMemoryNotEnoughClient() {
        if (!CoreConfig.shouldDisplayWarning("memory_not_enough")) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            if (client.player.tickCount > 40) {
                if (MESSAGE_LIMIT.tryDecrement()) {
                    // 仅替换这条提醒的模组前缀，保留当前语言的正文及帮助链接。
                    String message = Component.translatable("shuttershadow.core.memory_not_enough").getString()
                        .replaceFirst("^\\[[^\\]]*\\]", "[shuttershadow]");
                    CHelper.printChat(
                        Component.literal(message).append(
                            McHelper.getLinkText(LINK)
                        )
                    );
                }
            }
        }
    }
    
    public static boolean isMemoryNotEnough() {
        return memoryNotEnough;
    }
}

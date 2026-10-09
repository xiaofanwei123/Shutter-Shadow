package com.xfw.dimensionalexposure.core;


import net.minecraft.client.Minecraft;
import com.xfw.dimensionalexposure.core.CoreSettings;
import com.xfw.dimensionalexposure.core.chunk_loading.PerformanceLevel;
import com.xfw.dimensionalexposure.util.Helper;

import java.util.ArrayDeque;

// 仅供客户端使用。
// 保留客户端相机视距自适应，不再上报没有服务端消费者的性能等级。
/** 客户端每秒采样FPS及堆可用内存，滚动均值每5次采样更新性能档位。 */
public class ClientPerformanceMonitor {
    
    public static PerformanceLevel level = PerformanceLevel.medium;
    
    /** 一条FPS与可用堆MB采样。 */
    public static class Record {
        public final int FPS;
        public final int freeMemoryMB;
        
        /** 保存样本数值。 */
        public Record(int FPS, int freeMemoryMB) {
            this.FPS = FPS;
            this.freeMemoryMB = freeMemoryMB;
        }
    }
    
    private static final ArrayDeque<Record> records = new ArrayDeque<>();
    private static int averageFps = 60;
    private static int averageFreeMemoryMB = 1000;
    private static final int sampleNum = 20;
    
    private static int counter = 0;
    
    /** 游戏内记录FPS和最大堆减已使用堆的剩余MB，限制样本数并重算均值，每5样本调用updateLevel。 */
    public static void updateEverySecond(int newFps) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        
        long maxMemoryBytes = Runtime.getRuntime().maxMemory();
        long totalMemoryBytes = Runtime.getRuntime().totalMemory();
        long freeMemoryBytes = Runtime.getRuntime().freeMemory();
        long usedMemoryBytes = totalMemoryBytes - freeMemoryBytes;
        long actualFreeMemoryBytes = maxMemoryBytes - usedMemoryBytes;
        
        int freeMemoryMB = (int) Helper.toMiB(actualFreeMemoryBytes);
        
        records.addLast(new Record(newFps, freeMemoryMB));
        
        if (records.size() > sampleNum) {
            records.removeFirst();
        }
        
        averageFps = (int) records.stream().mapToInt(r -> r.FPS).average().orElse(60);
        averageFreeMemoryMB = (int) records.stream()
            .mapToInt(r -> r.freeMemoryMB).average().orElse(1000);
        
        counter++;
        if (counter % 5 == 0) {
            updateLevel();
        }
    }
    
    /** 配置关闭自动调整时固定good，否则按PerformanceLevel阈值取档。 */
    private static void updateLevel() {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        
        if (!CoreSettings.enableClientPerformanceAdjustment) {
            level = PerformanceLevel.good;
        }
        else {
            level = PerformanceLevel.getClientPerformanceLevel(averageFps, averageFreeMemoryMB);
        }
        
    }
}

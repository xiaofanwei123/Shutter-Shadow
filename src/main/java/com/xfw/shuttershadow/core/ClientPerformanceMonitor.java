package com.xfw.shuttershadow.core;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.Minecraft;
import com.xfw.shuttershadow.core.CoreSettings;
import com.xfw.shuttershadow.core.chunk_loading.PerformanceLevel;
import com.xfw.shuttershadow.util.Helper;

import java.util.ArrayDeque;

//@OnlyIn(Dist.CLIENT)
// 保留客户端相机视距自适应，不再上报没有服务端消费者的性能等级。
public class ClientPerformanceMonitor {
    
    public static PerformanceLevel level = PerformanceLevel.medium;
    
    public static class Record {
        public final int FPS;
        public final int freeMemoryMB;
        
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

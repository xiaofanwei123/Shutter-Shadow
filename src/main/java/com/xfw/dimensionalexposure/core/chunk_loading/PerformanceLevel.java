package com.xfw.dimensionalexposure.core.chunk_loading;



/** good/medium/bad客户端性能档位，只影响目标渲染有效距离，不改变服务端配置。 */
public enum PerformanceLevel {
    good, medium, bad;
    
    /** 平均FPS>50且剩余内存>800MB为good，>30且>300MB为medium，其他为bad。 */
    public static PerformanceLevel getClientPerformanceLevel(
        int averageFPS,
        int averageFreeMemoryMB
    ) {
        if (averageFPS > 50 && averageFreeMemoryMB > 800) {
            return good;
        }
        else if (averageFPS > 30 && averageFreeMemoryMB > 300) {
            return medium;
        }
        else {
            return bad;
        }
    }
    
    
    /** good保留原距离，medium减半且至少2，bad固定2。 */
    public static int getCameraRenderDistance(
        PerformanceLevel level, int originalDistance
    ) {
        if (level == good) {
            return originalDistance;
        }
        else if (level == medium) {
            return Math.max(2, originalDistance / 2);
        }
        else {
            return 2;
        }
    }
}

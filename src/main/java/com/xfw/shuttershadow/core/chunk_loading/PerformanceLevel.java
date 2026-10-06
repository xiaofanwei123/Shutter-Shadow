package com.xfw.shuttershadow.core.chunk_loading;
// Shuttershadow phase seven: relocated into the camera core.


public enum PerformanceLevel {
    good, medium, bad;
    
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

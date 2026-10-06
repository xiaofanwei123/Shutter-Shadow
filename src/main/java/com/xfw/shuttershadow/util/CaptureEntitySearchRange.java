package com.xfw.shuttershadow.util;

import java.util.function.Supplier;

/** 临时调整 Exposure 拍摄实体搜索半径，结束后恢复原值。 */
public final class CaptureEntitySearchRange {
    private static final ThreadLocal<Integer> RADIUS = new ThreadLocal<>();

    /** 工具类私有构造器。 */
    private CaptureEntitySearchRange() {
    }

    /** 优先使用当前线程的自定义搜索半径。 */
    public static double currentOr(double original) {
        Integer radius = RADIUS.get();
        return radius == null ? original : radius;
    }

    /** 在指定搜索半径下执行操作，并恢复之前的半径。 */
    public static <T> T withRadius(Integer radius, Supplier<T> query) {
        Integer previous = RADIUS.get();
        setRadius(radius);
        try {
            return query.get();
        } finally {
            setRadius(previous);
        }
    }

    /** 设置或清除当前线程的搜索半径。 */
    private static void setRadius(Integer radius) {
        if (radius == null) RADIUS.remove();
        else RADIUS.set(radius);
    }
}

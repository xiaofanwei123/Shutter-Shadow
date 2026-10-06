package com.xfw.shuttershadow.util;

import java.util.function.Supplier;

/** 同步实体查询的临时范围；不在照片、世界或线程之间保留覆盖值。 */
public final class CaptureEntitySearchRange {
    private static final ThreadLocal<Integer> RADIUS = new ThreadLocal<>();

    private CaptureEntitySearchRange() {
    }

    public static double currentOr(double original) {
        Integer radius = RADIUS.get();
        return radius == null ? original : radius;
    }

    /** null 使用原范围，嵌套的普通照片也不会继承外层生物胶卷的配置。 */
    public static <T> T withRadius(Integer radius, Supplier<T> query) {
        Integer previous = RADIUS.get();
        setRadius(radius);
        try {
            return query.get();
        } finally {
            setRadius(previous);
        }
    }

    private static void setRadius(Integer radius) {
        if (radius == null) RADIUS.remove();
        else RADIUS.set(radius);
    }
}

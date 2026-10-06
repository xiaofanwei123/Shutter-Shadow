package com.xfw.shuttershadow.util;


import it.unimi.dsi.fastutil.objects.ObjectList;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

// 通用辅助方法。
// Shuttershadow 第三轮修改：迁入原调试命令的 MiB 换算，供性能监测独立使用。
/** 共享日志、时间/容量换算、异常包装与fastutil列表算法。 */
public class Helper {
    
    public static final Logger LOGGER = LogManager.getLogger("shuttershadow");
    
    // TODO use separate logger for each class
    /** 以信息级别记录日志。 */
    @Deprecated
    public static void log(Object str) {
        LOGGER.info(str);
    }
    
    // TODO use separate logger for each class
    /** 以错误级别记录日志。 */
    @Deprecated
    public static void err(Object str) {
        LOGGER.error(str);
    }
    
    /** 将字节数换算为兆字节，保留整数除法行为。 */
    public static long toMiB(long bytes) {
        return bytes / 1024L / 1024L;
    }

    /** 将秒数换算为纳秒。 */
    public static long secondToNano(double second) {
        return (long) (second * 1000000000L);
    }
    
    /** 执行操作，并将受检异常包装为状态异常。 */
    public static <T> T noError(Callable<T> func) {
        try {
            return func.call();
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    
    /** 原地移除满足条件的列表元素。 */
    public static <T> void removeIf(ObjectList<T> list, Predicate<T> predicate) {
        int placingIndex = 0;
        for (int i = 0; i < list.size(); i++) {
            T curr = list.get(i);
            if (!predicate.test(curr)) {
                list.set(placingIndex, curr);
                placingIndex += 1;
            }
        }
        list.removeElements(placingIndex, list.size());
    }
    
    /** 按条件移除元素，支持提前停止并保留后续元素。 */
    public static <T> void removeIfWithEarlyExit(
        ObjectList<T> list, BiPredicate<T, MutableBoolean> predicate
    ) {
        MutableBoolean shouldStop = new MutableBoolean(false);
        
        int placingIndex = 0;
        for (int i = 0; i < list.size(); i++) {
            T curr = list.get(i);
            // 停止后保留后续元素，不再调用移除条件。
            if (shouldStop.booleanValue() || !predicate.test(curr, shouldStop)) {
                list.set(placingIndex, curr);
                placingIndex += 1;
            }
        }
        list.removeElements(placingIndex, list.size());
    }
    
    // 将数组列表作为整数索引到对象的映射，
    // 在对应位置为空时创建并保存对象。
    /** 扩展列表，并在指定索引为空时创建对应对象。 */
    public static <T> T arrayListComputeIfAbsent(
        List<T> arrayList,
        int index,
        Supplier<T> supplier
    ) {
        if (arrayList.size() <= index) {
            while (arrayList.size() <= index) {
                arrayList.add(null);
            }
        }
        T value = arrayList.get(index);
        if (value == null) {
            value = supplier.get();
            arrayList.set(index, value);
        }
        return value;
    }
    
}

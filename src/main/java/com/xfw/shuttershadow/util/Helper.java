package com.xfw.shuttershadow.util;
// Shuttershadow phase seven: relocated into the camera core.

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

// helper methods
// Shuttershadow 第三轮修改：迁入原调试命令的 MiB 换算，供性能监测独立使用。
public class Helper {
    
    public static final Logger LOGGER = LogManager.getLogger("shuttershadow");
    
    // TODO use separate logger for each class
    @Deprecated
    public static void log(Object str) {
        LOGGER.info(str);
    }
    
    // TODO use separate logger for each class
    @Deprecated
    public static void err(Object str) {
        LOGGER.error(str);
    }
    
    /** 沿用原整数除法，保持性能监测的阈值和舍入行为。 */
    public static long toMiB(long bytes) {
        return bytes / 1024L / 1024L;
    }

    public static long secondToNano(double second) {
        return (long) (second * 1000000000L);
    }
    
    public static <T> T noError(Callable<T> func) {
        try {
            return func.call();
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    
    /**
     * {@link ObjectList} does not override removeIf() so it's O(n^2)
     * {@link ArrayList#removeIf(Predicate)} uses a bitset to ensure integrity
     * in case of exception thrown but introduces performance overhead
     */
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
    
    /**
     * removeIf, but can early exit when the MutableBoolean is set to true
     */
    public static <T> void removeIfWithEarlyExit(
        ObjectList<T> list, BiPredicate<T, MutableBoolean> predicate
    ) {
        MutableBoolean shouldStop = new MutableBoolean(false);
        
        int placingIndex = 0;
        for (int i = 0; i < list.size(); i++) {
            T curr = list.get(i);
            // if stopped, it will be deemed as non-remove and not call the predicate
            if (shouldStop.booleanValue() || !predicate.test(curr, shouldStop)) {
                list.set(placingIndex, curr);
                placingIndex += 1;
            }
        }
        list.removeElements(placingIndex, list.size());
    }
    
    // treat ArrayList as an integer to object map
    // do computeIfAbsent
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

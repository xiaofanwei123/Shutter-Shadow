package com.xfw.dimensionalexposure.util;


import com.xfw.dimensionalexposure.DimensionalExposure;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.function.BooleanSupplier;

// 任务返回真时，从队列中移除。
/** 同步保护的可重试任务列表。 */
public class MyTaskList {
    private final ObjectList<BooleanSupplier> tasks = new ObjectArrayList<>();
    private final ObjectList<BooleanSupplier> tasksToAdd = new ObjectArrayList<>();
    
    // 任务执行过程中也允许添加新的任务。
    /** 把任务加入待合并列表。 */
    public synchronized void addTask(BooleanSupplier task) {
        tasksToAdd.add(task);
    }
    
    /** 合并新增任务，运行每个任务并原地删除完成项。 */
    public synchronized void processTasks() {
        tasks.addAll(tasksToAdd);
        tasksToAdd.clear();
        
        Helper.removeIf(tasks, task -> {
            try {
                return task.getAsBoolean();
            }
            catch (Throwable e) {
                DimensionalExposure.LOGGER.error("Failed to process task {}", task, e);
                return true;
            }
        });
    }
    
    /** 清空执行中和待新增的任务。 */
    public synchronized void forceClearTasks() {
        tasks.clear();
        tasksToAdd.clear();
    }
    
    /** 将操作包装为执行一次即可完成的任务。 */
    public static BooleanSupplier oneShotTask(Runnable runnable) {
        return () -> {
            runnable.run();
            return true;
        };
    }
    
    /** 延迟条件解除后，才执行原任务。 */
    public static BooleanSupplier withDelayCondition(BooleanSupplier shouldDelay, BooleanSupplier task) {
        return () -> !shouldDelay.getAsBoolean() && task.getAsBoolean();
    }
}

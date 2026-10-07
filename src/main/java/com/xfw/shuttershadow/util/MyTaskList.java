package com.xfw.shuttershadow.util;


import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.slf4j.Logger;

import java.util.function.BooleanSupplier;

// 任务返回真时，从队列中移除。
/** 同步保护的可重试任务列表。 */
public class MyTaskList {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /** 定义可在后续游戏刻或渲染帧重试的任务。 */
    public interface MyTask {
        /** 执行任务，返回是否已经完成。 */
        boolean runAndGetIsFinished();
    }
    
    private final ObjectList<MyTask> tasks = new ObjectArrayList<>();
    private final ObjectList<MyTask> tasksToAdd = new ObjectArrayList<>();
    
    // 任务执行过程中也允许添加新的任务。
    /** 把任务加入待合并列表。 */
    public synchronized void addTask(MyTask task) {
        tasksToAdd.add(task);
    }
    
    /** 合并新增任务，运行每个任务并原地删除完成项。 */
    public synchronized void processTasks() {
        tasks.addAll(tasksToAdd);
        tasksToAdd.clear();
        
        Helper.removeIf(tasks, task -> {
            try {
                return task.runAndGetIsFinished();
            }
            catch (Throwable e) {
                LOGGER.error("Failed to process task {}", task, e);
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
    public static MyTask oneShotTask(Runnable runnable) {
        return () -> {
            runnable.run();
            return true;
        };
    }
    
    /** 延迟条件解除后，才执行原任务。 */
    public static MyTask withDelayCondition(BooleanSupplier shouldDelay, MyTask task) {
        return () -> !shouldDelay.getAsBoolean() && task.runAndGetIsFinished();
    }
}

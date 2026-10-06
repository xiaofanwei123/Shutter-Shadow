package com.xfw.shuttershadow.util;
// Shuttershadow phase seven: relocated into the camera core.

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.slf4j.Logger;

import java.util.function.BooleanSupplier;

//NOTE if the task returns true, it will be deleted
//if the task returns false, it will be invoked again at next time
public class MyTaskList {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public interface MyTask {
        boolean runAndGetIsFinished();
    }
    
    private final ObjectList<MyTask> tasks = new ObjectArrayList<>();
    private final ObjectList<MyTask> tasksToAdd = new ObjectArrayList<>();
    
    // this method could be invoked while a task is running
    public synchronized void addTask(MyTask task) {
        tasksToAdd.add(task);
    }
    
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
    
    public synchronized void forceClearTasks() {
        tasks.clear();
        tasksToAdd.clear();
    }
    
    public static MyTask oneShotTask(Runnable runnable) {
        return () -> {
            runnable.run();
            return true;
        };
    }
    
    public static MyTask withDelayCondition(BooleanSupplier shouldDelay, MyTask task) {
        return () -> !shouldDelay.getAsBoolean() && task.runAndGetIsFinished();
    }
}

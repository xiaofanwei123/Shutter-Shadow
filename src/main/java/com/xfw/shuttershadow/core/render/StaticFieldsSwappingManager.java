package com.xfw.shuttershadow.core.render;


import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Supplier;

// 游戏会将部分维度专属状态保存在静态字段中，
// 例如背景雾渲染器的状态。
// 相机需要在同一帧渲染多个维度，
// 因此为各维度分别保存这些静态字段的值。
/** 泛型静态字段上下文交换堆栈。 */
public class StaticFieldsSwappingManager<Context> {
    private final Consumer<Context> copyFromObject;
    private final Consumer<Context> copyToObject;
    private final Supplier<Context> contextConstructor;
    
    /** 一个维度键与对应类型上下文对象。 */
    public static class ContextRecord<Ctx> {
        public ResourceKey<Level> dimension;
        public Ctx context;
        
        /** 保存维度及上下文。 */
        public ContextRecord(ResourceKey<Level> dimension, Ctx context) {
            this.dimension = dimension;
            this.context = context;
        }
    }
    
    private ResourceKey<Level> outerDimension;
    private Stack<ContextRecord<Context>> swappedContext = new Stack<>();
    
    // 此状态由外部调用方管理。
    public final Map<ResourceKey<Level>, ContextRecord<Context>> contextMap = new HashMap<>();
    
    /** 保存装入静态字段、保存静态字段和创建上下文三个回调。 */
    public StaticFieldsSwappingManager(
        Consumer<Context> copyFromObject,
        Consumer<Context> copyToObject,
        Supplier<Context> contextConstructor
    ) {
        
        this.copyFromObject = copyFromObject;
        this.copyToObject = copyToObject;
        this.contextConstructor = contextConstructor;
    }
    
    /** 返回是否在嵌套交换堆栈内。 */
    public boolean isSwapped() {
        return !swappedContext.empty();
    }
    
    /** 只有未交换时可设真实外层维度。 */
    public void setOuterDimension(ResourceKey<Level> dim) {
        Validate.isTrue(!isSwapped());
        
        outerDimension = dim;
    }
    
    /** 堆栈为空取外层维度，否则取栈顶维度。 */
    public ResourceKey<Level> getCurrentDimension() {
        if (swappedContext.empty()) {
            Validate.notNull(outerDimension);
            return outerDimension;
        }
        else {
            return swappedContext.peek().dimension;
        }
    }
    
    /** 保存当前维度静态状态，并装入目标维度上下文。 */
    public void pushSwapping(ResourceKey<Level> newDimension) {
        ResourceKey<Level> currentDimension = getCurrentDimension();
        
        ContextRecord<Context> oldContext = contextMap.get(currentDimension);
        ContextRecord<Context> newContext = contextMap.computeIfAbsent(newDimension, k -> {
            return new ContextRecord<>(newDimension, contextConstructor.get());
        });
        Validate.notNull(oldContext);
        Validate.notNull(newContext);
        
        swappedContext.push(newContext);
        
        transferDataFromStaticFieldsToObject(oldContext);
        
        transferDataFromObjectToStaticFields(newContext);
    }
    
    /** 保存退出维度的静态状态，并恢复上一层上下文。 */
    public void popSwapping() {
        ContextRecord<Context> outerContext = swappedContext.pop();
        ContextRecord<Context> innerContext = contextMap.get(getCurrentDimension());
        
        transferDataFromStaticFieldsToObject(outerContext);
        
        transferDataFromObjectToStaticFields(innerContext);
    }
    
    /** 将现有上下文记录装入静态字段。 */
    private void transferDataFromObjectToStaticFields(ContextRecord<Context> newContext) {
        if (newContext == null) {
            return;
        }
        copyFromObject.accept(newContext.context);
    }
    
    /** 将静态字段保存到现有上下文记录。 */
    private void transferDataFromStaticFieldsToObject(ContextRecord<Context> oldContext) {
        if (oldContext == null) {
            return;
        }
        copyToObject.accept(oldContext.context);
    }
    
    // 玩家跨维度传送时调用。
    /** 玩家真实传送时更新外层维度，并切换其静态状态。 */
    public void updateOuterDimensionAndChangeContext(ResourceKey<Level> newDimension) {
        Validate.isTrue(!isSwapped());
        Validate.notNull(outerDimension);
        
        ResourceKey<Level> oldDimension = this.outerDimension;
        
        transferDataFromStaticFieldsToObject(contextMap.get(oldDimension));
        
        transferDataFromObjectToStaticFields(contextMap.get(newDimension));
        
        outerDimension = newDimension;
    }
    
    
}

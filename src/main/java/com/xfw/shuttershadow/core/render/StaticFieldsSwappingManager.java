package com.xfw.shuttershadow.core.render;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Supplier;

//sometimes minecraft stores some dimension-specific things into static fields
//for example BackgroundRenderer
//we have to render multiple dimensions at the same time
//so we have to store multiple sets of these static fields
public class StaticFieldsSwappingManager<Context> {
    private final Consumer<Context> copyFromObject;
    private final Consumer<Context> copyToObject;
    private final Supplier<Context> contextConstructor;
    
    public static class ContextRecord<Ctx> {
        public ResourceKey<Level> dimension;
        public Ctx context;
        
        public ContextRecord(ResourceKey<Level> dimension, Ctx context) {
            this.dimension = dimension;
            this.context = context;
        }
    }
    
    private ResourceKey<Level> outerDimension;
    private Stack<ContextRecord<Context>> swappedContext = new Stack<>();
    
    //this will be managed by other classes
    public final Map<ResourceKey<Level>, ContextRecord<Context>> contextMap = new HashMap<>();
    
    public StaticFieldsSwappingManager(
        Consumer<Context> copyFromObject,
        Consumer<Context> copyToObject,
        Supplier<Context> contextConstructor
    ) {
        
        this.copyFromObject = copyFromObject;
        this.copyToObject = copyToObject;
        this.contextConstructor = contextConstructor;
    }
    
    public boolean isSwapped() {
        return !swappedContext.empty();
    }
    
    public void setOuterDimension(ResourceKey<Level> dim) {
        Validate.isTrue(!isSwapped());
        
        outerDimension = dim;
    }
    
    public ResourceKey<Level> getCurrentDimension() {
        if (swappedContext.empty()) {
            Validate.notNull(outerDimension);
            return outerDimension;
        }
        else {
            return swappedContext.peek().dimension;
        }
    }
    
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
    
    public void popSwapping() {
        ContextRecord<Context> outerContext = swappedContext.pop();
        ContextRecord<Context> innerContext = contextMap.get(getCurrentDimension());
        
        transferDataFromStaticFieldsToObject(outerContext);
        
        transferDataFromObjectToStaticFields(innerContext);
    }
    
    private void transferDataFromObjectToStaticFields(ContextRecord<Context> newContext) {
        if (newContext == null) {
            return;
        }
        copyFromObject.accept(newContext.context);
    }
    
    private void transferDataFromStaticFieldsToObject(ContextRecord<Context> oldContext) {
        if (oldContext == null) {
            return;
        }
        copyToObject.accept(oldContext.context);
    }
    
    //called when player teleports
    public void updateOuterDimensionAndChangeContext(ResourceKey<Level> newDimension) {
        Validate.isTrue(!isSwapped());
        Validate.notNull(outerDimension);
        
        ResourceKey<Level> oldDimension = this.outerDimension;
        
        transferDataFromStaticFieldsToObject(contextMap.get(oldDimension));
        
        transferDataFromObjectToStaticFields(contextMap.get(newDimension));
        
        outerDimension = newDimension;
    }
    
    
}

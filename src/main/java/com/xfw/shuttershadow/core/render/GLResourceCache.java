package com.xfw.shuttershadow.core.render;
// Shuttershadow phase seven: relocated into the camera core.

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.util.function.Consumer;

public class GLResourceCache {
    private final Consumer<int[]> generator;
    private final IntList bufferIds = new IntArrayList();
    
    public static GLResourceCache bufferCache = new GLResourceCache(GL15::glGenBuffers);
    public static GLResourceCache vertexArrayCache = new GLResourceCache(GL30::glGenVertexArrays);
    
    public GLResourceCache(Consumer<int[]> generator) {
        this.generator = generator;
    }
    
    public int getNewResourceId() {
        if (bufferIds.isEmpty()) {
            reserve(1000);
        }
        
        int taken = bufferIds.removeInt(bufferIds.size() - 1);
        return taken;
    }
    
    private void reserve(int num) {
        int[] buf = new int[num];
        generator.accept(buf);
        bufferIds.addElements(bufferIds.size(), buf);
    }
    
}

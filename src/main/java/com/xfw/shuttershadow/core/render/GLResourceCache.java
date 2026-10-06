package com.xfw.shuttershadow.core.render;


import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.util.function.Consumer;

/** 批量生成GL资源ID的池，减少逐ID调用GL生成器。 */
public class GLResourceCache {
    private final Consumer<int[]> generator;
    private final IntList bufferIds = new IntArrayList();
    
    public static GLResourceCache bufferCache = new GLResourceCache(GL15::glGenBuffers);
    public static GLResourceCache vertexArrayCache = new GLResourceCache(GL30::glGenVertexArrays);
    
    /** 保存GL批量生成函数。 */
    public GLResourceCache(Consumer<int[]> generator) {
        this.generator = generator;
    }
    
    /** 池空时一次reserve1000，再移出末尾ID。 */
    public int getNewResourceId() {
        if (bufferIds.isEmpty()) {
            reserve(1000);
        }
        
        int taken = bufferIds.removeInt(bufferIds.size() - 1);
        return taken;
    }
    
    /** 建立num长度数组，让generator填ID后追加到池。 */
    private void reserve(int num) {
        int[] buf = new int[num];
        generator.accept(buf);
        bufferIds.addElements(bufferIds.size(), buf);
    }
    
}

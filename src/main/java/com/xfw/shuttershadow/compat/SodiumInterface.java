package com.xfw.shuttershadow.compat;


import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.xfw.shuttershadow.mixin.compat.sodium.IESodiumWorldRenderer;

// 此接口仅供客户端渲染使用。
/** Sodium兼容入口持有空适配器或OnSodiumPresent。 */
public class SodiumInterface {
    
    /** 未装Sodium时的原版安全空适配器。 */
    public static class Invoker {
        /** 返回false。 */
        public boolean isSodiumPresent() {
            return false;
        }
        
        /** 返回null，不取得Sodium上下文。 */
        public Object acquireContext(int renderDistance) {
            return null;
        }
        
        /** 空实现，不交换manager状态。 */
        public void switchContextWithCurrentWorldRenderer(Object context) {
        
        }
        
        /** 空实现，不通知Sodium加载。 */
        public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
        
        }
        
        /** 空实现，不通知Sodium卸载。 */
        public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
        
        }
    }
    
    public static Invoker invoker = new Invoker();
    
    /** 实现 Sodium 的区块追踪和相机渲染上下文交换。 */
    public static class OnSodiumPresent extends Invoker {
        /** 返回true。 */
        @Override
        public boolean isSodiumPresent() {
            return true;
        }
        
        /** 按当前管理器和相机有效视距取得可复用的渲染上下文。 */
        @Override
        public Object acquireContext(int renderDistance) {
            SodiumWorldRenderer swr =
                ((LevelRendererExtension) Minecraft.getInstance().levelRenderer).sodium$getWorldRenderer();
            RenderSectionManager manager = ((IESodiumWorldRenderer) swr).ip_getRenderSectionManager();
            return ((IESodiumRenderSectionManager) manager).ip_acquireCameraContext(renderDistance);
        }
        
        /** 将相机的 Sodium 渲染上下文切换到当前世界渲染器。 */
        @Override
        public void switchContextWithCurrentWorldRenderer(Object context) {
            SodiumWorldRenderer swr =
                ((LevelRendererExtension) Minecraft.getInstance().levelRenderer).sodium$getWorldRenderer();
            RenderSectionManager renderSectionManager =
                ((IESodiumWorldRenderer) swr).ip_getRenderSectionManager();

            SodiumRenderingContext renderingContext = (SodiumRenderingContext) context;
            // 光影首次创建或资源重载可能替换 manager；旧列表所属 GPU 区域已被释放。
            if (renderingContext.owner != null && renderingContext.owner != renderSectionManager) {
                renderingContext.active = false;
                swr.scheduleTerrainUpdate();
                return;
            }
            renderingContext.owner = renderSectionManager;
            
            ((IESodiumRenderSectionManager) renderSectionManager)
                .ip_swapContext(renderingContext);
            renderingContext.active = !renderingContext.active;
            
            // 可见区段世代及 Iris 阴影状态仍由管理器共享，切换后继续重新判定地形。
            swr.scheduleTerrainUpdate();
        }
        
        /** 向目标ClientLevel的Sodium ChunkTracker加入HAS_BLOCK_DATA标记。 */
        @Override
        public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
            ChunkTrackerHolder.get(world)
                .onChunkStatusAdded(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }
        
        /** 从目标ClientLevel的Sodium ChunkTracker移除HAS_BLOCK_DATA标记。 */
        @Override
        public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
            ChunkTrackerHolder.get(world)
                .onChunkStatusRemoved(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }
    }
    
}

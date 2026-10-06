package com.xfw.shuttershadow.mixin.minecraft.client;


import com.xfw.shuttershadow.event.ClientCleanupEvent;
import com.xfw.shuttershadow.event.ClientExitEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.core.CoreSettings;
import com.xfw.shuttershadow.access.IEMinecraftClient;
import com.xfw.shuttershadow.core.ClientPerformanceMonitor;
import com.xfw.shuttershadow.core.render.RenderStates;
import com.xfw.shuttershadow.core.render.WorldRenderInfo;

/** 管理客户端远程世界更新、渲染性能采样及退出清理。 */
@Mixin(Minecraft.class)
// Shuttershadow 第四轮裁剪：移除完整 IP 模组的首次说明屏注入，保留渲染与世界生命周期。
// Shuttershadow 第六轮：撤销自动穿门检测，保留远程世界 tick 与状态同步。
public abstract class MixinMinecraft implements IEMinecraftClient {
    @Mutable
    @Shadow
    @Final
    public LevelRenderer levelRenderer;
    
    @Shadow
    private static int fps;
    
    /** Shadow 引用原 profiler，供后台世界 tick 的分析区间使用。 */
    @Shadow
    public abstract ProfilerFiller getProfiler();
    
    @Mutable
    @Shadow
    @Final
    private RenderBuffers renderBuffers;
    
    @Shadow
    @Final
    private static Logger LOGGER;
    
    @Shadow private Thread gameThread;
    
    

    
    // 在客户端世界和实体更新后执行。
    /** 客户端世界更新后更新远程世界，并发送客户端刻结束事件。 */
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;tick()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterClientTick(CallbackInfo ci) {
        getProfiler().push("shuttershadow_client_tick");
        
        // 同时更新远程世界。
        ClientWorldLoader.tick();
        
        RenderStates.setPartialTick(0);

        NeoForge.EVENT_BUS.post(new CoreSettings.PostClientTickEvent());
        
        getProfiler().pop();
    }
    
    /** 每秒采样客户端帧率，供相机性能监控使用。 */
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;runTick(Z)V",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;fps:I",
            shift = At.Shift.AFTER
        )
    )
    private void onSnooperUpdate(boolean tick, CallbackInfo ci) {
        ClientPerformanceMonitor.updateEverySecond(fps);
    }
    
    /** 世界替换或退出时发送生命周期事件并清理远程世界。 */
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;)V",
        at = @At("HEAD")
    )
    private void onSetWorld(ClientLevel clientLevel, CallbackInfo ci) {
        if (ClientWorldLoader.getIsInitialized()) {
            LOGGER.info("Client cleanup");
            NeoForge.EVENT_BUS.post(new ClientCleanupEvent());

            if (clientLevel == null) {
                LOGGER.info("Client exit world");
                NeoForge.EVENT_BUS.post(new ClientExitEvent());
            }

            ClientWorldLoader.cleanUp();
        }
        else {
            LOGGER.info("Client world updated but not counted as cleanup");
        }
    }
    
    // 避免破坏极佳画质模式的渲染状态。
    /** 远景渲染时禁用源世界的极佳画质透明后处理。 */
    @Inject(method = "Lnet/minecraft/client/Minecraft;useShaderTransparency()Z", at = @At("HEAD"), cancellable = true)
    private static void onIsFabulousGraphicsOrBetter(CallbackInfoReturnable<Boolean> cir) {
        if (WorldRenderInfo.isRendering()) {
            cir.setReturnValue(false);
        }
    }
    
    /** 设置 Minecraft.levelRenderer 引用，内部切世界/接管目标时使用。 */
    @Override
    public void ip_setWorldRenderer(LevelRenderer r) {
        levelRenderer = r;
    }
    
    /** 设置 Minecraft.renderBuffers 引用，临时绘制环境交换使用。 */
    @Override
    public void ip_setRenderBuffers(RenderBuffers arg) {
        renderBuffers = arg;
    }
    
    /** 返回 gameThread，供线程归属/任务路由检查。 */
    @Override
    public Thread ip_getRunningThread() {
        return gameThread;
    }
}

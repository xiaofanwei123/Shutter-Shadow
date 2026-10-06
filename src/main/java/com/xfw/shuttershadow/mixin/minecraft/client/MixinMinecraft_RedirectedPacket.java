package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.xfw.shuttershadow.core.ClientWorldLoader;
import com.xfw.shuttershadow.core.VanillaRuntimeHooks;
import com.xfw.shuttershadow.network.PacketRedirectionClient;

/** 让重定向数据包的客户端任务在正确维度上下文执行。 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft_RedirectedPacket extends ReentrantBlockableEventLoop<Runnable> {
    
    /** Mixin 继承 BlockableEventLoop 所需构造器，转发名称参数给父类。 */
    public MixinMinecraft_RedirectedPacket(String string) {
        super(string);
    }
    
    // 确保任务在重定向指定的维度中处理。
    /** 包装重定向包任务，使其在指定维度中执行并恢复上下文。 */
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;wrapRunnable(Ljava/lang/Runnable;)Ljava/lang/Runnable;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onCreateTask(Runnable runnable, CallbackInfoReturnable<Runnable> cir) {
        Minecraft this_ = (Minecraft) (Object) this;
        
        ResourceKey<Level> redirectedDimension = PacketRedirectionClient.clientTaskRedirection.get();
        if (redirectedDimension != null) {
            Runnable newRunnable = () -> {
                ClientWorldLoader.withSwitchedWorldFailSoft(redirectedDimension, runnable);
            };
            cir.setReturnValue(newRunnable);
        }
    }
    
    /** 覆写任务是否排队：当前游戏线程正在处理重定向消息时返回 false 使包及时在维度作用域执行。 */
    @VanillaRuntimeHooks
    @Override
    public boolean scheduleExecutables() {
        boolean onThread = isSameThread();
        
        if (onThread) {
            if (PacketRedirectionClient.getIsProcessingRedirectedMessage()) {
                return false;
            }
        }
        
        return this.runningTask() || !onThread;
    }
}

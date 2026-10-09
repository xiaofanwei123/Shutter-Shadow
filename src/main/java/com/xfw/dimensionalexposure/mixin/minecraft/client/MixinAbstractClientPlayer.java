package com.xfw.dimensionalexposure.mixin.minecraft.client;


import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import com.xfw.dimensionalexposure.access.IEAbstractClientPlayer;

/** 提供更新客户端玩家世界缓存的内部访问接口。 */
@Mixin(AbstractClientPlayer.class)
public class MixinAbstractClientPlayer implements IEAbstractClientPlayer {
    @Shadow
    @Final
    @Mutable
    public ClientLevel clientLevel;
    
    /** 更新玩家缓存的客户端世界，避免换维后仍引用旧世界。 */
    @Override
    public void ip_setClientLevel(ClientLevel clientWorld) {
        clientLevel = clientWorld;
    }
}

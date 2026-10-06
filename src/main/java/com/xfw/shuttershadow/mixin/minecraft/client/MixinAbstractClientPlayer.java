package com.xfw.shuttershadow.mixin.minecraft.client;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import com.xfw.shuttershadow.access.IEAbstractClientPlayer;

@Mixin(AbstractClientPlayer.class)
public class MixinAbstractClientPlayer implements IEAbstractClientPlayer {
    @Shadow
    @Final
    @Mutable
    public ClientLevel clientLevel;
    
    @Override
    public void ip_setClientLevel(ClientLevel clientWorld) {
        clientLevel = clientWorld;
    }
}

package com.xfw.shuttershadow.mixin.minecraft.common;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import com.xfw.shuttershadow.access.IEPlayerMoveC2SPacket;

@Mixin(ServerboundMovePlayerPacket.class)
public class MixinServerboundMovePlayerPacket_S implements IEPlayerMoveC2SPacket {
    private ResourceKey<Level> playerDimension;
    
    @Override
    public ResourceKey<Level> ip_getPlayerDimension() {
        return playerDimension;
    }
    
    @Override
    public void ip_setPlayerDimension(ResourceKey<Level> dim) {
        playerDimension = dim;
    }
    
    
}

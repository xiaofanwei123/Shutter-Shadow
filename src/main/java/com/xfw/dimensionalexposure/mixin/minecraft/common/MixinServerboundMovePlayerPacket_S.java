package com.xfw.dimensionalexposure.mixin.minecraft.common;


import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import com.xfw.dimensionalexposure.access.IEPlayerMoveC2SPacket;

/** 在移动包基类中保存四种子包共用的玩家维度。 */
@Mixin(ServerboundMovePlayerPacket.class)
public class MixinServerboundMovePlayerPacket_S implements IEPlayerMoveC2SPacket {
    private ResourceKey<Level> playerDimension;
    
    /** 读取 playerDimension，可为 null。 */
    @Override
    public ResourceKey<Level> ip_getPlayerDimension() {
        return playerDimension;
    }
    
    /** 写入 playerDimension，客户端构造/服务端解码调用。 */
    @Override
    public void ip_setPlayerDimension(ResourceKey<Level> dim) {
        playerDimension = dim;
    }
    
    
}

package com.xfw.shuttershadow.mixin.minecraft.client;


import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xfw.shuttershadow.access.IEPlayerMoveC2SPacket;

/** 移动包创建时保存玩家真实维度，避免延迟包坐标串维度。 */
@Mixin(ServerboundMovePlayerPacket.class)
public class MixinServerBoundMovePlayerPacket {
    /** 在移动包创建后记录玩家当前维度。 */
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void onConstruct(
        double x, double y, double z, float yaw, float pitch, boolean onGround,
        boolean changePosition, boolean changeLook, CallbackInfo ci
    ) {
        ResourceKey<Level> dimension = Minecraft.getInstance().player.level().dimension();
        ((IEPlayerMoveC2SPacket) this).ip_setPlayerDimension(dimension);
    }
}

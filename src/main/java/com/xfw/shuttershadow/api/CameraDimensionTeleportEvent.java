package com.xfw.shuttershadow.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** 服务端相机拍摄成功换维后的通知事件，不可取消已经完成的传送。 */
public final class CameraDimensionTeleportEvent extends PlayerEvent {
    private final ItemStack camera;
    private final ResourceKey<Level> sourceDimension;
    private final ResourceKey<Level> targetDimension;

    /** 保存拍摄相机快照和本次真实换维的来源、目标。 */
    public CameraDimensionTeleportEvent(ServerPlayer player, ItemStack camera,
                                        ResourceKey<Level> sourceDimension) {
        super(player);
        this.camera = camera.copy();
        this.sourceDimension = sourceDimension;
        this.targetDimension = player.level().dimension();
    }

    /** 返回本次已经完成换维的服务端玩家。 */
    @Override
    public ServerPlayer getEntity() {
        return (ServerPlayer) super.getEntity();
    }

    /** 返回相机快照的副本，监听者修改不会影响相机或其他监听者。 */
    public ItemStack getCamera() {
        return camera.copy();
    }

    /** 返回传送前的维度。 */
    public ResourceKey<Level> getSourceDimension() {
        return sourceDimension;
    }

    /** 返回传送完成时的目标维度。 */
    public ResourceKey<Level> getTargetDimension() {
        return targetDimension;
    }
}

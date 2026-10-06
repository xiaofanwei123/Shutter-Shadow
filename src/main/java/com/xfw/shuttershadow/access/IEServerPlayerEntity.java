package com.xfw.shuttershadow.access;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** MixinServerPlayer实现的原版玩家骑乘及世界变化桥，避免无缝换维度时多发原版瞬移。 */
public interface IEServerPlayerEntity {
    
    /** 暂抑制连接瞬移，执行原stopRiding。 */
    void ip_stopRidingWithoutTeleportRequest();
    
    /** 暂抑制连接瞬移，执行原startRiding目标载具。 */
    void ip_startRidingWithoutTeleportRequest(Entity newVehicle);
    
    /** 执行世界变化后的菜单/状态同步及NeoForge维度事件。 */
    void portal_worldChanged(ServerLevel fromWorld, Vec3 fromPos);
}

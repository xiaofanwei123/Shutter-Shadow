package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public interface IEServerPlayerEntity {
    
    void ip_stopRidingWithoutTeleportRequest();
    
    void ip_startRidingWithoutTeleportRequest(Entity newVehicle);
    
    void portal_worldChanged(ServerLevel fromWorld, Vec3 fromPos);
}

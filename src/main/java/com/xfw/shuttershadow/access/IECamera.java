package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

public interface IECamera {
    void ip_resetState(Vec3 pos, ClientLevel currWorld);
    
    void portal_setPos(Vec3 pos);
    
    void ip_setCameraY(float cameraY, float lastCameraY);
    
}

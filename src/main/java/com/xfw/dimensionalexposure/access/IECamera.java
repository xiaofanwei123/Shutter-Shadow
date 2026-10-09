package com.xfw.dimensionalexposure.access;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** MixinCamera实现的相机私有状态桥，限客户端渲染/截图。 */
public interface IECamera {
    /** 同时重设镜头位置和所处ClientLevel，更新水下/雾等世界判断。 */
    void ip_resetState(Vec3 pos, ClientLevel currWorld);
    
    /** 设置Camera位置及由原版setPosition联动的区块位置。 */
    void portal_setPos(Vec3 pos);

    /** 同步相机四元数和方向向量，远景绘制使用曝光时的真实镜头方向。 */
    void ip_setRotation(float yaw, float pitch);
    
    /** 设置当前和历史Camera眼高以支持支架临时相机。 */
    void ip_setCameraY(float cameraY, float lastCameraY);
    
}

package com.xfw.shuttershadow.access;

import com.xfw.shuttershadow.core.ServerRuntimeState;

/** MixinMinecraftServer实现的每服务器运行状态桥。 */
public interface IEMinecraftServer {
    /** 返回该服务器独立ServerRuntimeState。 */
    ServerRuntimeState ip_getPerServerInfo();
}

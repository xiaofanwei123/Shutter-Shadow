package com.xfw.dimensionalexposure.access;

/** MixinLevel实现的世界运行线程桥。 */
public interface IEWorld {
    /** 返回Level.thread，供重定向和票据线程校验。 */
    Thread portal_getThread();
}

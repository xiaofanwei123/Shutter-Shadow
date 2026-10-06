package com.xfw.shuttershadow.access;

/** MixinRenderSection实现的GPU网格生命周期/遍历标记桥。 */
public interface IERenderSection {
    /** 取消重建任务、复位编译状态并标脏供区块卸载。 */
    void portal_fullyReset();
    
    /** 取可见遍历时间戳。 */
    long portal_getMark();
    
    /** 写可见遍历时间戳去重。 */
    void portal_setMark(long arg);
    
    /** 更新section在当前Preset中的一维index。 */
    void portal_setIndex(int arg);
    
}

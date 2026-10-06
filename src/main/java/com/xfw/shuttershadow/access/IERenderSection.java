package com.xfw.shuttershadow.access;
// Shuttershadow phase seven: relocated into the camera core.

public interface IERenderSection {
    void portal_fullyReset();
    
    long portal_getMark();
    
    void portal_setMark(long arg);
    
    void portal_setIndex(int arg);
    
}

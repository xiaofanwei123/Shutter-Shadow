package com.xfw.dimensionalexposure.util;


import org.apache.commons.lang3.Validate;

/** 不降为负数的次数预算，限制重复日志/聊天提醒。 */
public class CountDownInt {
    // 非原子计数，仅用于近似限制日志数量。
    private int value;
    
    /** 拒绝负初值并保存剩余次数。 */
    public CountDownInt(int value) {
        Validate.isTrue(value >= 0);
        this.value = value;
    }
    
    /** 尝试扣除一次预算，返回是否成功。 */
    public boolean tryDecrement() {
        if (value > 0) {
            value--;
            return true;
        }
        else {
            return false;
        }
    }
    
    /** 返回预算是否为0。 */
    public boolean isZero() {
        return value == 0;
    }
}

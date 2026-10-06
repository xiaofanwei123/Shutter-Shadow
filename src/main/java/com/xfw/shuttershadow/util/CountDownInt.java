package com.xfw.shuttershadow.util;
// Shuttershadow phase seven: relocated into the camera core.

import org.apache.commons.lang3.Validate;

/**
 * Used for limiting log count.
 * Common usage:
 * if (countDownInt.tryDecrement()) {
 *     LOGGER.info(...);
 *     if (countDownInt.isZero()) {
 *         LOGGER.info("Logging reached limit.");
 *     }
 * }
 */
public class CountDownInt {
    // not atomic. this doesn't need to be accurate.
    private int value;
    
    public CountDownInt(int value) {
        Validate.isTrue(value >= 0);
        this.value = value;
    }
    
    public boolean tryDecrement() {
        if (value > 0) {
            value--;
            return true;
        }
        else {
            return false;
        }
    }
    
    public boolean isZero() {
        return value == 0;
    }
}

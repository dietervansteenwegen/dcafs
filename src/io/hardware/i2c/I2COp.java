package io.hardware.i2c;

import com.diozero.api.I2CDevice;

import java.util.ArrayList;

public interface I2COp {

    ArrayList<Integer> doOperation(I2CDevice device);
    void setDelay( long millis);
    long getDelay();
    String toString();

}

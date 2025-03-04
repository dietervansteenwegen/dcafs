package util.data;

import java.math.BigDecimal;

public interface NumericVal {

    String id(); //get the id
    String asValueString();
    BigDecimal toBigDecimal(); // get the value as a BigDecimal
    String unit();
    double value(); // Get the value as a double
    int intValue(); // Get the value as an integer
    void updateValue(double val); // update the value based on the double
    default int order(){
        return -1;
    }
}

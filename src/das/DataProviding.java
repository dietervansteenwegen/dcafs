package das;

public interface DataProviding {
    String parseRTline( String line, String error );
    DoubleVal getDoubleVal( String param );
    double getRealtimeValue(String parameter, double defVal, boolean createIfNew);
    double getRealtimeValue(String parameter, double bad);
    String getRealtimeText(String parameter, String bad);
}

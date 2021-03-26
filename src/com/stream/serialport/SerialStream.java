package com.stream.serialport;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

import com.fazecast.jSerialComm.*;
import com.stream.Writable;
import com.stream.BaseStream;

import util.xml.XMLfab;
import util.tools.Tools;
import util.xml.XMLtools;
import worker.Datagram;
import org.tinylog.Logger;
import org.w3c.dom.Element;

/**
 * Variant of the StreamHandler class that is
 */
public class SerialStream extends BaseStream implements Writable {

    protected SerialPort serialPort;
    private byte[] buffer = new byte[1024];
    ByteBuffer recBuffer= ByteBuffer.wrap(buffer);

    public SerialStream(String port, BlockingQueue<Datagram> dQueue, String label, int priority) {
        super("", label, dQueue);
        setPriority(priority);
        setPort(port);
    }

    public SerialStream(BlockingQueue<Datagram> dQueue, Element stream) {
        super(dQueue,stream);
    }
    protected String getType(){
        return "serial";
    }
    public boolean setPort(String port) {
        try{
            serialPort = SerialPort.getCommPort(port);
        }catch( SerialPortInvalidPortException e ){
            Logger.error("No such serial port: " + port);
            Logger.error(e);
            return false;
        }
        return true;
    }

    public String getInfo() {
        return "SERIAL [" + id + "|" + label + "] " + serialPort.getSystemPortName() + " | " + getSerialSettings();
    }

    public boolean connect() {
        return this.doConnect(eol);
    }

    private boolean doConnect(String delimiter) {
        eol = delimiter;
        connectionAttempts++;

        if (serialPort == null) {
            return false;
        }

        if (serialPort.openPort()) {
            this.addListener();
            Logger.info("Connected to serialport " + serialPort.getSystemPortName());
            listeners.forEach( l -> l.notifyOpened(id) );
        } else {
            Logger.info("FAILED connection to serialport " + serialPort.getSystemPortName());
            return false;
        }
        return true;
    }

    private void addListener() {
        if (serialPort == null)
            return;

        if (eol.isEmpty()) {
            serialPort.addDataListener(new SerialPortDataListener() {
                @Override
                public int getListeningEvents() {
                    return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
                    processListenerEvent( event.getReceivedData() );
                }
            });
        } else {
            serialPort.addDataListener(new MessageListener(eol));
        }
    }

    private final class MessageListener implements SerialPortMessageListenerWithExceptions {

        byte[] deli;

        public MessageListener(String delimiter) {
            this.deli = delimiter.getBytes();
        }

        @Override
        public int getListeningEvents() {
            return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
        }

        @Override
        public byte[] getMessageDelimiter() {
            return deli;
        }

        @Override
        public boolean delimiterIndicatesEndOfMessage() {
            return true;
        }

        @Override
        public void serialEvent(SerialPortEvent event) {
            processMessageEvent( event.getReceivedData() );
        }

        @Override
        public void catchException(Exception e) {
            Logger.error(e);
        }
    }
    protected void processListenerEvent( byte[] data ){
        Logger.info("ReceivedEvent: "+new String(data));
        Logger.tag("RAW").warn(priority + "\t" + label + "\t" + new String(data));
    }
    protected void processMessageEvent(byte[] data){
        String message = new String(data).replace(eol, "");

        if( log ) {        // If the message isn't an empty string and logging is enabled, store the data with logback
            Logger.tag("RAW").warn(priority + "\t" + label+"|"+id + "\t" + message);
        }

        Datagram d = new Datagram(message, priority, label);
        d.setOriginID(id);

        if (debug) {
            Logger.info(id + " -> " + message);
            Logger.info(Tools.fromBytesToHexString(message.getBytes()));
        }
        if( !targets.isEmpty() ){
            try {
                targets.stream().forEach(dt -> {
                    try{
                        dt.writeLine(message);
                    }catch(Exception e){
                        Logger.error(id+" -> Something bad while writeLine to "+dt.getID());
                        Logger.error(e);
                    }
                });
                targets.removeIf(wr -> !wr.isConnectionValid()); // Clear inactive
            }catch(Exception e){
                Logger.error(id+" -> Something bad in serialport");
                Logger.error(e);
            }
        }

        dQueue.add(d);

        long p = Instant.now().toEpochMilli() - timestamp; // Calculate the time between 'now' and when the previous
        // message was received
        if (p > 0) { // If this time is valid
            passed = p; // Store it
        }
        timestamp = Instant.now().toEpochMilli(); // Store the timestamp of the received message
    }

    public void alterSerialSettings(String settings) {
        if (serialPort == null) {
            return;
        }

        String[] split = settings.split(",");
        int stopbits;
        int parity = SerialPort.NO_PARITY;

        if (split.length == 1)
            split = settings.split(";");

        switch (split[2]) {           
            case "1.5":
                stopbits = SerialPort.ONE_POINT_FIVE_STOP_BITS;
                break;
            case "2":
                stopbits = SerialPort.TWO_STOP_BITS;
                break;
            case "1":
            default:
                stopbits = SerialPort.ONE_STOP_BIT;
                break;
        }
        if (split.length > 3) {
            switch (split[3]) {
                
                case "even":
                    parity = SerialPort.EVEN_PARITY;
                    break;
                case "odd":
                    parity = SerialPort.ODD_PARITY;
                    break;
                case "mark":
                    parity = SerialPort.MARK_PARITY;
                    break;
                case "space":
                    parity = SerialPort.SPACE_PARITY;
                    break;
                case "none":
                default:
                    parity = SerialPort.NO_PARITY;
                    break;
            }
        }

        serialPort.setBaudRate(Tools.parseInt(split[0], 19200));
        serialPort.setNumDataBits(Tools.parseInt(split[1], 8));
        serialPort.setNumStopBits(stopbits);
        serialPort.setParity(parity);
    }

    public void alterSerialSettings(int baudrate, int databits, int stopbits, int parity) {
        serialPort.setBaudRate(baudrate);
        serialPort.setNumDataBits(databits);
        serialPort.setNumStopBits(stopbits);
        serialPort.setParity(parity);
    }

    public String getSerialSettings() {
        return serialPort.getBaudRate() + "," + serialPort.getNumDataBits() + "," + getStopbits() + "," + getParity();
    }

    private String getParity() {
        switch (serialPort.getParity()) {
            case SerialPort.EVEN_PARITY:
                return "even";
            case SerialPort.ODD_PARITY:
                return "odd";
            case SerialPort.MARK_PARITY:
                return "mark";
            case SerialPort.SPACE_PARITY:
                return "space";
            case SerialPort.NO_PARITY:
            default:
                return "none";
        }
    }

    private String getStopbits() {
        switch (serialPort.getNumStopBits()) {
            case SerialPort.ONE_POINT_FIVE_STOP_BITS:
                return "1.5";
            case SerialPort.TWO_STOP_BITS:
                return "2";
            case SerialPort.ONE_STOP_BIT:
            default:
                return "1";
        }
    }

    public void setBaudrate(int baudrate) {
        serialPort.setBaudRate(baudrate);
    }

    public static boolean portExists( String port ){
        for( SerialPort p : SerialPort.getCommPorts() ){
            if( p.getSystemPortName().equalsIgnoreCase(port))
                return true;
        }
        return false;
    }
    public static String portList( ){
        StringJoiner join = new StringJoiner(", ");
        join.setEmptyValue("No serial ports found.");        
        for( SerialPort p : SerialPort.getCommPorts() )
            join.add(p.getSystemPortName());
        return join.toString();
    }
    /* ************************************** W R I T I N G ************************************************************/
    /**
     * Sending data that will be appended by the default newline string.
     * 
     * @param message The data to send.
     * @return True If nothing was wrong with the connection
     */
    @Override
    public synchronized boolean writeLine(String message) {
        return writeString(message + eol);
    }

    /**
     * Sending data that won't be appended with anything
     * 
     * @param message The data to send.
     * @return True If nothing was wrong with the connection
     */
    @Override
    public synchronized boolean writeString(String message) {
        return writeBytes(message.getBytes());
    }

    /**
     * Sending a hexidecimal value
     * 
     * @param value The hex to send
     * @return True If nothing was wrong with the connection
     */
    public synchronized boolean writeHex(int value) {
        byte[] ar = { (byte) value };
        return writeBytes(ar);
    }

    /**
     * Sending data that won't be appended with anything
     * 
     * @param data The data to send.
     * @return True If nothing was wrong with the connection
     */
    public synchronized boolean writeBytes(byte[] data) {
        if (serialPort != null && serialPort.isOpen()) {
            var res = serialPort.writeBytes(data, data.length);
            if( res==-1){
                Logger.error(id+" -> Error writing to port "+serialPort.getSystemPortName());
            }else if( res != data.length ){
                Logger.error(id+" -> The amount of bytes written does not equal expected.");
            }
            return  res == data.length;
        }else if( serialPort==null){
            Logger.error(id+" -> No write done, serialport is null.");
        }else if( !serialPort.isOpen()){
            Logger.error(id+" -> No write done, serialport is closed.");
        }
        return false;
    }

    public boolean disconnect() {
        if (serialPort != null && serialPort.isOpen())
            return serialPort.closePort();
        return false;
    }

    @Override
    public boolean isConnectionValid() {
        if (serialPort == null)
            return false;
        return serialPort.isOpen();
    }

    @Override
    public String getID() {
        return id;
    }

    @Override
    public Writable getWritable() {
        return this;
    }

    @Override
    protected boolean readExtraFromXML(Element stream) {
        if (!setPort(XMLtools.getChildValueByTag(stream, "port", ""))) {
            return false;
        }
        alterSerialSettings(XMLtools.getChildValueByTag(stream, "serialsettings", "19200,8,1,none"));
        return true;
    }
    @Override
    protected boolean writeExtraToXML(XMLfab fab) {
        fab.alterChild("serialsettings",getSerialSettings());
        fab.alterChild("port",serialPort.getSystemPortName());
        return true;
    }
    @Override
    public long getLastTimestamp() {
        return timestamp;
    }


}
package io.stream;

import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.*;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public abstract class BaseStream {

    protected BlockingQueue<Datagram> dQueue;
    
    /* Pretty much the local descriptor */
	protected int priority = 1;				// Priority of the messages received, used by DataWorker
	protected String label = "";			// The label that determines what needs to be done with a message
	protected String id="";				    // A readable name for the handler
    protected long readerIdleSeconds =-1;				    // Time in seconds before the connection is killed due to idle

    /* Things regarding the connection*/
    protected long timestamp = System.currentTimeMillis();  // Timestamp of the last received message, init so startup doesn't show error message
    protected long passed = -1;							    // Time passed (in ms) between the last two received messages

    protected ArrayList<Writable> targets = new ArrayList<>();
    protected ArrayList<StreamListener> listeners = new ArrayList<>();

    protected String eol="\r\n";

    protected boolean reconnecting=false;
    protected int connectionAttempts=0;

    protected boolean debug=false;
    protected boolean clean=true;
    protected boolean log=true;
    protected boolean echo=false;

    protected ScheduledFuture<?> reconnectFuture=null;
    protected ArrayList<TriggerAction> triggeredActions = new ArrayList<>();

    protected ValStore store;

    private static final String XML_PRIORITY_TAG="priority";
    private static final String XML_STREAM_TAG="stream";
    protected enum TRIGGER{OPEN,IDLE,CLOSE,HELLO,WAKEUP, IDLE_END}

    protected EventLoopGroup eventLoopGroup;		    // Eventloop used by the netty stuff

    protected BaseStream( String id, String label, BlockingQueue<Datagram> dQueue){
        this.id=id;
        this.label=label;
        this.dQueue=dQueue;
    }
    protected BaseStream( BlockingQueue<Datagram> dQueue, Element stream ){
        this.dQueue=dQueue;
        readFromXML(stream);
    }
    protected BaseStream(){}

    public void setEventLoopGroup( EventLoopGroup eventLoopGroup ){
        this.eventLoopGroup = eventLoopGroup;
    }

    protected boolean readFromXML( Element stream ){

        if (!stream.getAttribute("type").equalsIgnoreCase(getType())) {
            Logger.error("Not a "+getType()+" stream element.");
            return false;
        }

        id = XMLtools.getStringAttribute( stream, "id", ""); 
        label = XMLtools.getChildStringValueByTag( stream, "label", "");    // The label associated fe. nmea,sbe38 etc
        priority = XMLtools.getChildIntValueByTag( stream, XML_PRIORITY_TAG, 1);	 // Determine priority of the sensor
        
        log = XMLtools.getChildStringValueByTag(stream, "log", "yes").equals("yes");

        // delimiter
        String deli = XMLtools.getChildStringValueByTag( stream, "eol", "\r\n");
        if( deli.equalsIgnoreCase("\\0"))
            deli="";// Delimiter used, default carriage return + line feed
        eol = Tools.getDelimiterString(deli);

        // ttl
		String ttlString = XMLtools.getChildStringValueByTag( stream, "ttl", "-1");
        if( !ttlString.equals("-1") ){
			if( Tools.parseInt(ttlString, -999) != -999) // Meaning no time unit was added, use the default s
                ttlString += "s";
			readerIdleSeconds = TimeTools.parsePeriodStringToSeconds(ttlString);
        }
        if( XMLtools.getChildBooleanValueByTag(stream, "echo", false) ){
            enableEcho();
        }
        // Store
        var storeOpt = ValStore.build(stream);
        if( storeOpt.isPresent())
            store = storeOpt.get();

        // cmds
        triggeredActions.clear();
        for( Element cmd : XMLtools.getChildElements(stream, "cmd") ){
            String c = cmd.getTextContent();
            String when = XMLtools.getStringAttribute(cmd,"when","open");
            triggeredActions.add(new TriggerAction(when, c));
        }
        for( Element cmd : XMLtools.getChildElements(stream, "write") ){
            String c = cmd.getTextContent();
            String when = XMLtools.getStringAttribute(cmd,"when","hello");
            triggeredActions.add(new TriggerAction(when, c));
        }
        return readExtraFromXML(stream);
    }
    protected boolean reloadStore(Path settingsPath, RealtimeValues rtvals){
        if( store !=null)
            store.removeRealtimeValues(rtvals);

        var dig = XMLdigger.goIn(settingsPath,"dcafs").goDown("streams").goDown("stream","id",id);
        var eleOpt = dig.current();
        if( eleOpt.isPresent()){
            var storeOpt = ValStore.build(eleOpt.get());
            if( storeOpt.isPresent()) {
                store = storeOpt.get();
                store.shareRealtimeValues(rtvals);
                return true;
            }
        }
        return false;
    }
    public Optional<ValStore> getValStore(){
        return Optional.ofNullable(store);
    }
    protected abstract boolean readExtraFromXML( Element stream );
    protected abstract boolean writeExtraToXML( XMLfab fab );

    /**
     * Write the stream to xml using an XMLfab
     * @param fab XMLfab with parent pointing to streams node
     * @return True if ok
     */
    protected boolean writeToXML( XMLfab fab ){
        Optional<Element> stream = fab.getChild(XML_STREAM_TAG, "id", id); // look for a child node based on id

        // Look through the child nodes for one that matches tag,id,value
        if( fab.selectChildAsParent(XML_STREAM_TAG, "id", id).isEmpty() ){
            // Not found so create it, taken in account we create a child to get a parent...
            fab.addChild(XML_STREAM_TAG).attr("id",id).attr("type",getType())
                    .down(); //make it the parent
        }

        var list = fab.getChildren("*");


        if( !label.equalsIgnoreCase("void")) {
            fab.alterChild("label", label);
        }else{
            fab.removeChild(label);
        }
        if( list.stream().anyMatch( x -> x.getNodeName().equalsIgnoreCase("ttl") ) ){
            if( readerIdleSeconds ==-1){
                fab.removeChild("ttl");
            }
        }
        if( readerIdleSeconds != -1 ){
            fab.addChild("ttl",TimeTools.convertPeriodtoString(readerIdleSeconds, TimeUnit.SECONDS));
        }

        if( list.stream().anyMatch( x -> x.getNodeName().equalsIgnoreCase("log") ) ){
            fab.alterChild("log",log?"yes":"no");
        }else if( !log ){
            fab.addChild("log","no");
        }
        
        if( list.stream().anyMatch( x -> x.getNodeName().equalsIgnoreCase(XML_PRIORITY_TAG) ) ){
            fab.alterChild(XML_PRIORITY_TAG,""+priority);
        }else if( priority!=1 ){
            fab.addChild(XML_PRIORITY_TAG,""+ priority );
        }
        if( echo )
            fab.alterChild("echo", echo?"yes":"no");
        fab.alterChild("eol", Tools.getEOLString(eol) );

        fab.clearChildren("cmd"); // easier to just remove first instead of checking if existing
        for( var tr : triggeredActions){
            switch(tr.trigger){
                case OPEN: case IDLE: case CLOSE: case IDLE_END:
                    fab.addChild("cmd",tr.data);
                    break;
                case HELLO:  case WAKEUP:
                    fab.addChild("write",tr.data);
                    break;
            }
            if( tr.trigger==TRIGGER.IDLE_END){
                fab.attr("when","!idle");
            }else{
                fab.attr("when",tr.trigger.toString().toLowerCase());
            }
        }
        return writeExtraToXML(fab);
    }

    // Abstract methods
    public abstract boolean connect();
    public abstract boolean disconnect();
    public abstract boolean isConnectionValid();
    public abstract long getLastTimestamp();
    public abstract String getInfo();
    protected abstract String getType();

    /* Getters & Setters */
    public void setLabel( String label ){
        this.label=label;
    }
    public String getLabel( ){
        return label;
    }
    public void setPriority(int priority ){
		this.priority=priority;
    }
    
    public void addListener( StreamListener listener ){
		listeners.add(listener);
    }
    public boolean removeListener( StreamListener listener ){
		return listeners.remove(listener);
    }

    public void setID( String id ){
        this.id=id;
    }
    public String getID(){
        return id;
    }
    public boolean isWritable(){
        return this instanceof Writable;
    }

    /**
     * Set the maximum time passed since data was received before the connection is considered idle
     * @param seconds The time in seconds
     */
    public void setReaderIdleTime(long seconds){
        this.readerIdleSeconds = seconds;
    }

    /* Requesting data */
    public synchronized boolean addTarget(Writable writable ){
        if( writable == null){
            Logger.error("Tried adding request to "+id+" but writable is null");
            return false;
        }
        if( targets.contains(writable)){
            Logger.info(id +" -> Already has "+writable.getID()+" as target, not adding.");
            return false;
        }
        targets.removeIf( x -> x.getID().equals(writable.getID())&&writable.getID().contains(":")); // if updated
        targets.add( writable );
        Logger.info("Added request from "+writable.getID()+ " to "+id);
        return true;
    }
    public boolean removeTarget(String id ){
        return targets.removeIf(entry -> entry.getID().equalsIgnoreCase(id));
    }
    public boolean removeTarget(Writable wr ){
		return targets.remove(wr);
	}
	public int clearTargets(){
        int total=targets.size();
        targets.clear();
        return total;
    }
	public int getRequestsSize(){
		return targets.size();
    }
    public String listTargets(){
        StringJoiner join = new StringJoiner(", ");
        targets.forEach(wr -> join.add(wr.getID()));
        return join.toString();
    }
    /* Echo */
    public boolean enableEcho(){
        if( this instanceof Writable ){
            targets.add((Writable)this );
            echo=true;
            return true;
        }
        return false;
    }
    public boolean disableEcho(){
        if( this instanceof Writable ){
            echo=false;
            targets.removeIf(r -> r.getID().equalsIgnoreCase(id));
            return true;
        }
        return false;
    }
    public boolean hasEcho(){
        return echo;
    }
    /* ******************************** TRIGGERED ACTIONS *****************************************************/
    public boolean addTriggeredAction(String when, String action ){
        var t = new TriggerAction(when, action);
        if( t.trigger==null)
            return false;
        triggeredActions.add(t);
        return true;
    }
    public void applyTriggeredAction(TRIGGER trigger ){
        for( TriggerAction cmd : triggeredActions){
            if( cmd.trigger!=trigger)
                continue;

            if( cmd.trigger==TRIGGER.HELLO || cmd.trigger==TRIGGER.WAKEUP ){
                Logger.info(id+" -> "+cmd.trigger+" => "+cmd.data);
                if( this instanceof Writable )
                    ((Writable) this).writeLine(cmd.data);
                continue;
            }
            Logger.info(id+" -> "+cmd.trigger+" => "+cmd.data);
            if( this instanceof Writable ){
                dQueue.add( Datagram.system(cmd.data).writable((Writable)this) );
            }else{
                dQueue.add( Datagram.system(cmd.data) );
            }
        }
    }
    public List<String> getTriggeredActions(TRIGGER trigger ){
        return triggeredActions.stream().filter(x -> x.trigger==trigger).map(x -> x.data).collect(Collectors.toList());
    }
    private static TRIGGER convertTrigger( String trigger ){
        switch (trigger){
            case "open":   return TRIGGER.OPEN;
            case "close":  return TRIGGER.CLOSE;
            case "idle":   return TRIGGER.IDLE;
            case "!idle":  return TRIGGER.IDLE_END;
            case "hello":  return TRIGGER.HELLO;
            case "wakeup": case "asleep": return TRIGGER.WAKEUP;
            default : Logger.error("Unknown trigger requested : "+trigger); return null;
        }
    }
    protected static class TriggerAction {
        String data;
        TRIGGER trigger;

        TriggerAction(TRIGGER trigger, String data ){
            this.trigger=trigger;
            this.data =data;
            Logger.info("Added action : "+trigger+" -> "+data);
        }
        TriggerAction(String trigger, String command){
            this(convertTrigger(trigger),command);
        }
    }
}

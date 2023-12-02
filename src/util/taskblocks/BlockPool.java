package util.taskblocks;

import das.CommandPool;
import io.Writable;
import io.email.Email;
import io.email.EmailSending;
import io.stream.StreamManager;
import io.stream.tcp.TcpServer;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.*;

public class BlockPool {

    HashMap<String,MetaBlock> startBlocks = new HashMap<>();
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    CommandPool cp;
    RealtimeValues rtvals;
    StreamManager ss;
    TcpServer ts;
    EmailSending es;

    public BlockPool( CommandPool cp, RealtimeValues rtvals, StreamManager ss ){
        this.cp=cp;
        this.rtvals=rtvals;
        this.ss=ss;
    }
    public void setTransServer(TcpServer ts){
        this.ts=ts;
    }
    public void addEmailSending(EmailSending es){
        this.es=es;
    }
    public Optional<MetaBlock> getStartBlock(String id, boolean createIfNew){
        if( startBlocks.get(id)==null && createIfNew){
            var v = new MetaBlock(id,"");
            startBlocks.put(id,v);
            return Optional.of(v);
        }
        return Optional.ofNullable(startBlocks.get(id));
    }
    public boolean runStartBlock( String id){
        return getStartBlock(id,false).map( tb->tb.start(null) ).orElse(false);
    }
    public void readFromXML( Path script){

        var fab=XMLfab.withRoot(script,"tasklist");
        // Go through the sets
        fab.selectChildAsParent("tasksets");

        for( var ts : fab.getChildren("taskset")){
            String tsId = XMLtools.getStringAttribute(ts,"id","");
            String info = XMLtools.getStringAttribute(ts,"info","");
            String failure = XMLtools.getStringAttribute(ts,"failure","");
            String req = XMLtools.getStringAttribute(ts,"req","");
            String type = XMLtools.getStringAttribute(ts,"run","");

            BlockTree tree = BlockTree.trunk( getStartBlock(tsId,true).get() );
            var start = tree.getMetaBlock().info(info).type(type);
            if( !failure.isEmpty() ){
                start.failure(getStartBlock(failure,true).get());
            }

            startBlocks.put( tsId,tree.getMetaBlock());

            if( !req.isEmpty()){ // add the req step if any
                tree.addTwig( CheckBlock.prepBlock(rtvals,req));
            }

            for( var t : XMLtools.getChildElements(ts)){
                readTask(t,tree);
            }
        }
        fab.selectChildAsParent("tasks");

        for( var t : fab.getChildren("task")){
            String tid = XMLtools.getStringAttribute(t,"id","");
            BlockTree tree;
            if( tid.isEmpty() ){
                tree = BlockTree.trunk( getStartBlock("init",true).get() );
            }else{
                tree = BlockTree.trunk( new MetaBlock(tid,"Lose task") );
            }
            readTask(t,tree);
        }
        for( var b : startBlocks.values()){
            Logger.info( b.getTreeInfo());
        }
    }
    public void readTask( Element t, BlockTree tree){
        var trigger = XMLtools.getStringAttribute(t,"trigger","");
        if( !trigger.isEmpty()){
            tree.branchOut( TriggerBlock.prepBlock(scheduler,trigger));
        }

        // Read and process the state attribute
        var state = XMLtools.getStringAttribute(t,"state","");
        state=state.replace("always",""); // remove the old default
        if(state.contains(":")){
            var stat = state.split(":");
            tree.branchOut( CheckBlock.prepBlock(rtvals, "{t:"+stat[0]+"} equals "+stat[1]));
        }

        // Read and process the req attribute
        var req = XMLtools.getStringAttribute(t,"req","");
        if( !req.isEmpty()){
            tree.branchOut( CheckBlock.prepBlock(rtvals,req));
        }
        // Read and process the check attribute
        var check = XMLtools.getStringAttribute(t,"check","");

        // Read and process the output attribute
        var output = XMLtools.getStringAttribute(t,"output","").split(":");
        var data = t.getTextContent();
        var values = data.split(";");

        AbstractBlock outblock=null;
        switch(output[0]){
            case "":case "system":
                outblock = CmdBlock.prepBlock(cp, t.getTextContent());
                break;
            case "email":
                String attachment = XMLtools.getStringAttribute(t,"attachment","");
                tree.branchOut( CmdBlock.prepBlock(cp,values[1]));
                var email = Email.to(output[1]).subject(values[0]).attachment(attachment).content(values[1]);
                tree.addTwig( EmailBlock.prepBlock(es,email));
                tree.branchIn();
                break;
            case "stream":
                var bsOpt = ss.getStream(output[1]);
                if( bsOpt.isPresent() && bsOpt.get() instanceof Writable ) {
                    var bl = WritableBlock.prepBlock( bsOpt.get(), t.getTextContent());
                    var reply = XMLtools.getStringAttribute(t,"reply","");
                    if( !reply.isEmpty()) {
                        bl.addReply(reply, scheduler);
                    }
                    outblock=bl;
                }else{
                    Logger.error("No such Base stream: "+output[1]);
                    return;
                }
                break;
            case "trans":
                if( ts!=null ){
                    var h = ts.getClientWritable(output[1]);
                    if( h.isPresent()){
                        outblock=WritableBlock.prepBlock( h.get(), t.getTextContent());
                    }else{
                        Logger.error("No such client connected: "+output[1]);
                    }
                }else{
                    Logger.error("No TCP server defined");
                    return;
                }
                break;
            case "manager":
                var text = t.getTextContent().split(":");
                switch( text[0]){
                    case "taskset":
                        outblock = getStartBlock(text[1],true).get() ;
                        break;
                    case "stop":
                        var b = getStartBlock(text[1],false);
                        if( b.isPresent() ){
                            outblock = ControlBlock.prepBlock(b.get(),"stop");
                        }else{
                            var mb=new MetaBlock(text[1],"");
                            startBlocks.put(text[1],mb);
                            outblock = ControlBlock.prepBlock(mb,"stop");
                        }
                        break;
                }
                break;
        }
        // If an outblock was created
        if( outblock!=null) {
            // and a check isn't requested
            if (check.isEmpty()) {
                tree.addTwig(outblock);
            }else{// and a check needs to be done
                tree.branchOut(outblock);
                tree.addTwig(CheckBlock.prepBlock(rtvals,check));
                tree.branchIn();
            }
        }
        if( !trigger.isEmpty()){
            tree.branchIn();
        }
        if( !req.isEmpty()){
            tree.branchIn();
        }
        if( !state.isEmpty()){
            tree.branchIn();
        }
    }
}

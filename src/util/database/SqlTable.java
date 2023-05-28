package util.database;

import util.data.RealtimeValues;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.ValStore;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class SqlTable {

    String name;

    enum COLUMN_TYPE {
        INTEGER, REAL, TEXT, TIMESTAMP, EPOCH, OBJECT, LOCALDTNOW, UTCDTNOW, DATETIME
    }

    ArrayList<Column> columns = new ArrayList<>();
    HashMap<String, ValStore> stores = new HashMap<>();
    boolean ifnotexists = false;
    boolean server = false;

    HashMap<String,PrepStatement> preps = new HashMap<>();
    String lastError="";

    private boolean readFromDatabase=false;
    private long prepCount=0;

    public SqlTable(String name) {
        this.name = name;
        preps.put("", new PrepStatement());
    }

    /**
     * By default, this assumes it's for sqlite, with this it's toggled to be for a server instead
     */
    public void toggleServer(){
        server=true;
    }
    public boolean isServer(){return server;}
    public void setLastError(String error ){
        this.lastError=error;
    }

    /**
     * Get the last error that has occurred during sql operations
     * @param clear Clear the error after returning it
     * @return The last error message
     */
    public String getLastError( boolean clear ){
        String t = lastError;
        if( clear)
            lastError= "";
        return t;
    }

    /**
     * Read the setup of the table from a xml element
     * @param tbl The element containing the setup
     * @return An optional table, empty if something went wrong
     */
    public static Optional<SqlTable> readFromXml(Element tbl) {
        String tableName = tbl.getAttribute("name").trim();
        SqlTable table = SqlTable.withName(tableName);
        boolean ok = true;

        for (Element node : XMLtools.getChildElements(tbl)) {
            if (node != null) {
                String val = node.getTextContent().trim();
                if (val.equals(".")) {
                    Logger.error("Column still without a name! " + tableName);
                    ok = false;
                    break;
                }
                String rtval = XMLtools.getStringAttribute(node,"rtval","");

                switch (node.getNodeName()) {
                    case "real" -> table.addReal(val, rtval);
                    case "integer", "int" -> table.addInteger(val, rtval);
                    case "timestamp" -> {
                        if (rtval.isEmpty()) {
                            table.addTimestamp(val);
                        } else {
                            table.addText(val, rtval);
                        }
                    }
                    case "millis" -> {
                        if (rtval.isEmpty()) {
                            table.addEpochMillis(val);
                        } else {
                            table.addInteger(val, rtval);
                        }
                    }
                    case "text" -> table.addText(val, rtval);
                    case "localdtnow" -> table.addLocalDateTime(val, rtval, true);
                    case "utcdtnow", "utcnow" -> table.addUTCDateTime(val, rtval, true);
                    case "datetime" -> table.addLocalDateTime(val, rtval, false);
                    default -> {
                        Logger.error("Unknown column specified " + node.getNodeName() + " for " + table.getName());
                        return Optional.empty();
                    }
                }

                /* Setup of the column */
                String setup = node.getAttribute("setup").toLowerCase();
                table.setPrimaryKey(setup.contains("primary"));
                table.setNotNull(setup.contains("notnull"));
                table.setUnique(setup.contains("unique"));
                if (node.hasAttribute("def"))
                    table.withDefault(node.getAttribute("def"));
            }
        }
        if (ok)
            return Optional.of(table);
        return Optional.empty();
    }

    /**
     * Store the setup of a table in xml
     *
     * @param fab   The xmlfab to use, pointing to the database as parent node
     * @param build If true, the xml is build at the end
     */
    public void writeToXml(XMLfab fab, boolean build ){
        fab.addChild("table").attr("name",name).down();
        for( var col : columns ){
            fab.addChild(col.type.toString().toLowerCase(),col.title);
            if( !col.rtval.isEmpty() && !col.rtval.equalsIgnoreCase(name+"_"+col.title)) {
                fab.attr("rtval", col.rtval);
            }else{
                fab.removeAttr("rtval");
            }
            fab.removeAttr("alias");
            if( !col.defString.isEmpty())
                fab.attr("def",col.defString);
            String setup = (col.primary?"primary ":"")+(col.notnull?"notnull ":"")+(col.unique?"unique ":"");
            if( !setup.isEmpty())
                fab.attr("setup",setup.trim());
        }
        fab.up();

        if (build) {
            fab.build();
        }
    }
    /**
     * Create a SQLiteTable object for a table with the given name
     * 
     * @param name The name of the table
     * @return The created object
     */
    public static SqlTable withName(String name) {
        return new SqlTable(name);
    }

    /**
     * Flag that the sqltable was read from a database (and not from xml)
     */
    public void flagAsReadFromDB(){
        readFromDatabase=true;
    }

    /**
     * Clear the flag that states that the table was read from the database
     */
    public void clearReadFromDB(){
        readFromDatabase=false;
    }

    /**
     * Check if the table was rad the the database (instead of xml)
     * @return True if read from database
     */
    public boolean isReadFromDB(){
        return readFromDatabase;
    }
    /**
     * Get the name of the table
     * 
     * @return The table name
     */
    public String getName() {
        return name;
    }

    /**
     * Check if the build query of this table would use 'if not exists'
     * @return True ifnotexists flag is high
     */
    public boolean hasIfNotExists() {
        return ifnotexists;
    }

    /**
     * Add a column that contains integer data
     *
     * @param title The title of the oolumn
     */
    public void addInteger(String title) {
        addColumn(new Column(title, (name + "_" + title).toLowerCase(), COLUMN_TYPE.INTEGER));
    }

    /**
     * Add a column that contains integer data, using the given rtval to link to
     * rtvals
     *
     * @param title The title of the column
     * @param rtval The rtval to use to find the data
     */
    public void addInteger(String title, String rtval) {
        addColumn(new Column(title, rtval, COLUMN_TYPE.INTEGER));
    }

    /**
     * Add a column that contains real data
     *
     * @param title The title of the column
     */
    public void addReal(String title) {
        addColumn(new Column(title, (name + "_" + title).toLowerCase(), COLUMN_TYPE.REAL));
    }

    /**
     * Add a column that contains real data, using the given rtval to link to rtvals
     *
     * @param title The title of the column
     * @param rtval The rtval to use to find the data
     */
    public void addReal(String title, String rtval) {
        addColumn(new Column(title, rtval, COLUMN_TYPE.REAL));
    }

    /**
     * Add a column that contains text data
     *
     * @param title The title of the column
     */
    public void addText(String title) {
        addColumn(new Column(title, (name + "_" + title).toLowerCase(), COLUMN_TYPE.TEXT));
    }

    /**
     * Add a column that contains text data, using the given rtval to link to rtvals
     *
     * @param title The title of the column
     * @param rtval The rtval to use to find the data
     */
    public void addText(String title, String rtval) {
        addColumn(new Column(title, rtval, COLUMN_TYPE.TEXT));
    }

    /* Timestamp */
    /**
     * Add a column that contains timestamp data (in text format)
     *
     * @param title The title of the column
     */
    public void addTimestamp(String title) {
        addColumn(new Column(title, (name + "_" + title).toLowerCase(), COLUMN_TYPE.TIMESTAMP));
    }

    public void addLocalDateTime(String title, String rtval, boolean now) {
        addColumn(new Column(title, rtval, now?COLUMN_TYPE.LOCALDTNOW:COLUMN_TYPE.DATETIME));
    }
    public void addUTCDateTime(String title, String rtval, boolean now) {
        addColumn(new Column(title, rtval, now?COLUMN_TYPE.UTCDTNOW:COLUMN_TYPE.DATETIME));
    }

    /**
     * Add a column that contains timestamp data (in integer format).
     *
     * @param title The title of the column
     */
    public void addEpochMillis(String title) {
        addColumn(new Column(title, (name + "_" + title).toLowerCase(), COLUMN_TYPE.EPOCH));
    }

    public void withDefault(String def) {
        int index = columns.size() - 1;
        columns.get(index).setDefault(def);
    }
    /**
     * Add a column to the collection of columns, this also updates the PreparedStatement
     * @param c The column to add
     */
    private void addColumn( Column c ){
        columns.add(c);
        preps.get("").addColumn(columns.size()-1);
    }
    /**
     * Define whether the last created column is the primary key
     *
     * @param pk True if primary key, false if not
     */
    public void setPrimaryKey(boolean pk) {
        int index = columns.size() - 1;
        columns.get(index).primary = pk;
    }
    /**
     * Define whether the last created column is not allowed to be null
     *
     * @param nn True if not allowed to be null, false if so
     */
    public void setNotNull(boolean nn) {
        int index = columns.size() - 1;
        columns.get(index).notnull = nn;
    }

    /**
     * Define that the last created column must only contain unique values
     * 
     * @return This object
     */
    public SqlTable setUnique(boolean unique) {
        columns.get(columns.size() - 1).unique = unique;
        return this;
    }
    /**
     * Check if this table has columns
     * 
     * @return True if it is not empty
     */
    public boolean hasColumns() {
        return !columns.isEmpty();
    }

    /**
     * Get the CREATE statement to make this table
     * 
     * @return The CREATE statement in string format
     */
    public String create() {
        return toString();
    }

    /**
     * Get all the info about this table
     * 
     * @return Info message
     */
    public String getInfo() {
        StringJoiner join = new StringJoiner("\r\n", "Table '" + name + "'\r\n", "");
        for (Column column : columns) {
            join.add("> " + column.toString()
                    + (column.rtval.equals(column.title) ? "" : " (rtval=" + column.rtval + ")"));
        }
        return join + "\r\n";
    }

    /**
     * Get the CREATE statement to make this table
     * 
     * @return The CREATE statement in string format
     */
    public String toString() {
        if( columns.isEmpty() ) {
            return "CREATE TABLE" + (ifnotexists ? " IF NOT EXISTS " : " ") + name+";";
        }
        StringJoiner join = new StringJoiner(", ",
                "CREATE TABLE " + (ifnotexists ? "IF NOT EXISTS" : "") + " " + name + " (", " );");
        columns.forEach(x -> join.add(x.toString()));
        return join.toString();
    }

    public int getRecordCount() {        
        return preps.values().stream().mapToInt( p -> p.getData().size()).sum();        
    }
    public boolean hasRecords(){
        return preps.values().stream().anyMatch( p -> !p.getData().isEmpty());
    }
    public boolean hasRecords(String id){
        return getPrep(id).map( p -> !p.getData().isEmpty()).orElse(false);
    }
    private Optional<PrepStatement> getPrep( String id ){
        return Optional.ofNullable(preps.get(id));
    }
    public String getPreparedStatement( String id ) {
        if( id.isEmpty())
            return getPreparedStatement();
        return getPrep(id).map( PrepStatement::getStatement).orElse("");
    }
    public Set<String> getPreps(){
        return preps.keySet();
    }
    public String getPreparedStatement() {
        PrepStatement prep = preps.get("");
        if( prep.getStatement().isEmpty() )
            buildDefStatement();
        return prep.getStatement();
    }
    public int fillStatement( String id, PreparedStatement ps ) {
        PrepStatement prep = preps.get(id);
        if( prep==null || ps==null)
            return -1;

        int count=0;
        int size = prep.getData().size();
        for (int a=0;a<size;a++) { //foreach can cause concurrency issues

            if( size > prep.getData().size() ){
                Logger.error(name+":"+(id.isEmpty()?"def":id) +" -> Data shrunk during processing...? ori:"+size+" now "+prep.getData().size());
                return -3;
            }
            Object[] d = prep.getData().get(a);
            if( d==null ){
                Logger.error( name+":"+(id.isEmpty()?"def":id)+" -> Asked for a record at "+a+" which is null... skipping");
                continue;
            }
            int index = 0;
            try {
                for ( int colIndex : prep.getIndexes() ) {
                    Column c = columns.get(colIndex);
                    try{
                        if( d[index] instanceof OffsetDateTime )
                            d[index]=asTimestamp((OffsetDateTime) d[index]);
                        ps.setObject( index+1,d[index] );
                        index++;
                    }catch( java.lang.ClassCastException | NullPointerException e){
                        Logger.error(name+":"+id+" -> Failed to cast "+d[index]+" to "+c.type);
                        Logger.error(e);
                        break;
                    }                    
                }
                count++;                
                ps.addBatch();
            } catch ( Exception e ) {
                Logger.error(e);
                return -1;
            } 
        }
        return count;
    }
    public static Timestamp asTimestamp(OffsetDateTime offsetDateTime) {
        if (offsetDateTime != null) {
            return Timestamp.valueOf(offsetDateTime.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        }
        else
            return null;
    }
    public int clearRecords( String id, long[] updateCounts ){
        PrepStatement prep = preps.get(id);
        if( prep==null){
            Logger.error(name+" -> No such prep: "+id);
            return -1;
        }
        var dd = prep.getData();
        int offset=0;
        prep.enableLock();
        for( int index=0;index<updateCounts.length;index++){
            if( updateCounts[index]==Statement.EXECUTE_FAILED){                
                dd.remove(index-offset);
                offset++;      
            }  
        }
        prep.disableLock();
        return offset;
    }
    public void clearRecords(String id, int count ){
        PrepStatement prep = preps.get(id);
        if( prep==null){
            Logger.error(name+" -> No such prep: "+id);
            return;
        }
        prep.enableLock();
        var dd = prep.getData();
        if (count > 0) {
            dd.subList(0, count).clear();
        }

        if( !dd.isEmpty() ){
            Logger.debug(id+" -> Not all records removed ("+dd.size()+" left)");
            // Probably not needed in live situations? Not sure how those are introduced
            dd.removeIf( Objects::isNull );
        }
        prep.disableLock();
    }
    public int doInsert(Object[] values){
        return getPrep("").map( p -> p.addData(values)?1:0).orElse(-1);
    }
    /**
     * Use the given rtvals to fill in the create statement, rtval/title must match elements
     * @param rtvals The RealtimeValues object containing the values
     * @return The INSERT statement or an empty string if a value wasn't found
     */
    public boolean buildInsert( RealtimeValues rtvals ,String macro ){
        return buildInsert("",rtvals,macro);
    }
    /**
     * Use the given rtvals object and macro to fill in the INSERT statement (@macro defined in xml)
     * @param rtvals The RealtimeValues object containing the values
     * @param macro The string to replace the @macro in the rtval with
     * @return The INSERT statement or an empty string if a value wasn't found
     */
    public boolean buildInsert( String id, RealtimeValues rtvals, String macro ){
       
        PrepStatement prep = preps.get(id);
        if( prep==null){
            Logger.error(name+" -> No such prep: "+id);
            return false;
        }

        Object[] record = new Object[columns.size()];

        if( stores.get(macro)==null) {
            stores.put(macro, new ValStore(id));
        }
        var store = stores.get(macro);
        int index=-1;
        for( int colPos : prep.getIndexes() ){
            Column col = columns.get(colPos);
            index++;    
            String def = col.getDefault();
            
            String ref = col.rtval.replace("@macro", macro);
            Object val = null;
            try{
                if( col.type == COLUMN_TYPE.EPOCH){
                    record[index]=Instant.now().toEpochMilli();
                    if( index >= store.size())
                        store.addEmptyVal();
                    continue;
                }else if( col.type == COLUMN_TYPE.TEXT){
                    if( index >= store.size() ){
                        var v = rtvals.getTextVal(ref);
                        if( v.isPresent()){
                            store.addAbstractVal(v.get());
                        }else{
                            store.addEmptyVal();
                        }
                    }else if( store.isEmptyAt(index)){
                        var v = rtvals.getTextVal(ref);
                        if( v.isPresent()){
                            store.setAbstractVal(index,v.get());
                        }
                    }
                    val = store.getValueAt(index);
                }else if( col.type == COLUMN_TYPE.INTEGER ){
                    if( index >= store.size() ){
                        var v = rtvals.getIntegerVal(ref);
                        if( v.isPresent()){
                            store.addAbstractVal(v.get());
                        }else{
                            store.addEmptyVal();
                        }
                    }else if( store.isEmptyAt(index)){
                        var v = rtvals.getIntegerVal(ref);
                        if( v.isPresent()){
                            store.setAbstractVal(index,v.get());
                        }
                    }
                    val = store.getIntValueAt(index);
                    if( val==null ){
                        if( col.hasDefault) {
                            Logger.debug(id + " -> Didn't find integer with id " + ref);
                        }else {
                            Logger.error(id + " -> Didn't find integer with id " + ref);
                        }
                        val = col.hasDefault?NumberUtils.createInteger(def):null;
                    }
                }else if( col.type == COLUMN_TYPE.REAL){
                    if( index >= store.size() ){
                        var v = rtvals.getRealVal(ref);
                        if( v.isPresent()){
                            store.addAbstractVal(v.get());
                        }else{
                            store.addEmptyVal();
                        }
                    }else if( store.isEmptyAt(index)){
                        var v = rtvals.getRealVal(ref);
                        if( v.isPresent()){
                            store.setAbstractVal(index,v.get());
                        }
                    }
                    val = store.getRealValueAt(index);
                    if( val==null ){
                        if( col.hasDefault) {
                            Logger.debug(id + " -> Didn't find real with id " + ref);
                        }else {
                            Logger.error(id + " -> Didn't find real with id " + ref);
                        }
                        val = col.hasDefault?NumberUtils.createDouble(def):null;
                    }
                }else if( col.type == COLUMN_TYPE.LOCALDTNOW){
                    if( index >= store.size() )
                        store.addEmptyVal();
                    val = OffsetDateTime.now();
                    if( !server )
                        val = val.toString();
                }else if( col.type == COLUMN_TYPE.UTCDTNOW){
                    if( index >= store.size() )
                        store.addEmptyVal();
                    val = OffsetDateTime.now(ZoneOffset.UTC);
                    if( !server )
                        val = val.toString();
                }else if( col.type == COLUMN_TYPE.DATETIME){
                    if( index >= store.size() ) {
                        var v = rtvals.getTextVal(ref);
                        if( v.isPresent()){
                            store.addAbstractVal(v.get());
                        }else{
                            store.addEmptyVal();
                        }
                    }else if( store.isEmptyAt(index)){
                        var v = rtvals.getTextVal(ref);
                        if( v.isPresent()){
                            store.setAbstractVal(index,v.get());
                        }
                    }
                    val = TimeTools.parseDateTime(store.getValueAt(index),"yyyy-MM-dd HH:mm:ss.SSS");
                    if( !server )
                        val = val.toString();
                }
            }catch( NullPointerException e ){
                Logger.error("Null pointer when looking for "+ref + " type:"+col.type);
            }

            if( val == null && col.hasDefault ){
                record[index]= def;
            }else{
                if( val == null) {
                    Logger.error("Couldn't find " + ref + " for " + name + " aborted insert.");
                    return false;
                }
                record[index] = val;
            }
        }
        prepCount++;
        return prep.addData(record);
    }
    public long getPrepCount(){
        return prepCount;
    }
    public String getColumnTypes(){
        var join = new StringJoiner(",");
        columns.forEach( c->join.add(String.valueOf(c.type)) );
        return join.toString();
    }
    /**
     * Inner class that holds all the info regarding a single column
     */
    private class Column{
        COLUMN_TYPE type;
        String title="";
        String rtval ="";
        boolean unique=false;
        boolean notnull=false;
        boolean primary=false;

        boolean hasDefault=false;
        String defString="";

        public Column( String title, String rtval, COLUMN_TYPE type){
            this.title=title;
            if( rtval.equals("")) // if no rtval is given, we assume it's the same as the title
                rtval=name+"_"+title;
            this.rtval =rtval;
            this.type=type;
            switch (type) {
                case TIMESTAMP, EPOCH -> notnull = true;
                // these aren't allowed to be null by default
                default -> {
                }
            }
        }
        public void setDefault(String def){
            this.defString=def;
            hasDefault=true;
        }
        public String getDefault(){
            if( type==COLUMN_TYPE.TEXT ){
                return "'"+defString+"'";
            }else{
                return defString;
            }
        }
        /**
         * Get the string that will be used in the CREATE statement for this column
         */
        public String toString(){ 
            
            if( (type == COLUMN_TYPE.TIMESTAMP||type == COLUMN_TYPE.LOCALDTNOW || type== COLUMN_TYPE.UTCDTNOW) && !server ) // Timestamp should be timestamp on a server
                return title+" TEXT" + (unique?" UNIQUE":"") + (notnull?" NOT NULL":"")+(primary?" PRIMARY KEY":"");
            if( type == COLUMN_TYPE.EPOCH )
                return title+" REAL" + (unique?" UNIQUE":"") + (notnull?" NOT NULL":"")+(primary?" PRIMARY KEY":"");
            if( (type == COLUMN_TYPE.LOCALDTNOW || type== COLUMN_TYPE.UTCDTNOW)  )
                return title+" timestamptz" + (unique?" UNIQUE":"") + (notnull?" NOT NULL":"")+(primary?" PRIMARY KEY":"");
            return title+" "+type + (unique?" UNIQUE":"") + (notnull?" NOT NULL":"")+(primary?" PRIMARY KEY":"");
        }
    }

    /**
     * Adds a blank table node according to the format to the fab with current parent the database node;
     * @param fab The fab with the database node as current parent
     * @param tableName The name of the table
     * @param format The format of the table, t=timestamp,r=real,i=int,c=text,m=epochmillis
     * @return True if written
     */
    public static boolean addBlankToXML( XMLfab fab, String tableName, String format ) {

        fab.addChild("table").attr("name",tableName).down();

        for( char c : format.toCharArray() ){
            switch (c) {
                case 't' -> fab.addChild("timestamp", "columnname");
                case 'u' -> fab.addChild("utcnow", "columnname");
                case 'r' -> fab.addChild("real", "columnname");
                case 'i' -> fab.addChild("integer", "columnname");
                case 'c' -> fab.addChild("text", "columnname");
                case 'm' -> fab.addChild("epochmillis", "columnname");
            }
        }
        return fab.build();
    }
    private void buildDefStatement(){
        PrepStatement stat = preps.get("");

        StringJoiner qMarks = new StringJoiner(",", "", ");");
        StringJoiner cols = new StringJoiner(",", "INSERT INTO " + name + " (", ") VALUES (");
        stat.getIndexes().forEach(c -> {
            qMarks.add("?");
            cols.add( columns.get(c).title );
        });
        stat.setStatement( cols + qMarks.toString() );
    }
    private static class PrepStatement{
        ArrayList<Object[]> data = new ArrayList<>();
        ArrayList<Object[]> temp = new ArrayList<>();
        ArrayList<Integer> indexes = new ArrayList<>(); // which columns
        String statement="";
        boolean locked=false;

        public void addColumn( int index ){
            indexes.add(index);
        }
        public List<Integer> getIndexes(){
            return indexes;        
        }
        public List<Object[]> getData(){
            return data;
        }
        public boolean addData( Object[] d ){

            if( d.length!=indexes.size() )
                return false;
            if(locked){
                return temp.add(d);
            }else{
                return data.add(d);
            }

        }
        public void setStatement( String stat ){
            statement=stat;
        }
        public String getStatement(){            
            return statement;
        }
        public void enableLock(){locked=true;}
        public void disableLock(){
            locked=false;
            if( !temp.isEmpty()) {
                data.addAll(temp);
                //Logger.info("Moved " + temp.size() + " from temp");
                temp.clear();
            }
        }
    }
}
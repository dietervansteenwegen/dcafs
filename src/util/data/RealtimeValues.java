package util.data;

import das.Commandable;
import das.IssuePool;
import io.Writable;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.gis.Waypoints;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A storage class
 *
 */
public class RealtimeValues implements DataProviding, Commandable {

	/* Data stores */
	private final ConcurrentHashMap<String, RealVal> realVals = new ConcurrentHashMap<>(); // doubles
	private final ConcurrentHashMap<String, IntegerVal> integerVals = new ConcurrentHashMap<>(); // integers
	private final ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>(); // strings
	private final ConcurrentHashMap<String, FlagVal> flagVals = new ConcurrentHashMap<>(); // booleans

	private Waypoints waypoints; // waypoints
	private final IssuePool issuePool;

	/* Data update requests */
	private final HashMap<String, List<Writable>> textRequest = new HashMap<>();

	/* General settings */
	private final Path settingsPath;

	/* Patterns */
	private final Pattern words = Pattern.compile("[a-zA-Z]+[_:\\d]*[a-zA-Z\\d]+\\d*"); // find references to *val

	/* Other */
	private final BlockingQueue<Datagram> dQueue; // Used to issue triggered cmd's

	boolean readingXML=false;

	public RealtimeValues( Path settingsPath,BlockingQueue<Datagram> dQueue ){
		this.settingsPath=settingsPath;
		this.dQueue=dQueue;

		readFromXML( XMLfab.withRoot(settingsPath,"dcafs", "rtvals") );
		issuePool = new IssuePool(dQueue, settingsPath,this);
	}

	/* ************************************ X M L ****************************************************************** */
	/**
	 * Read the rtvals node in the settings.xml
	 */
	public void readFromXML( Element rtvalsEle ){

		double defReal = XMLtools.getDoubleAttribute(rtvalsEle,"realdefault",Double.NaN);
		String defText = XMLtools.getStringAttribute(rtvalsEle,"textdefault","");
		boolean defFlag = XMLtools.getBooleanAttribute(rtvalsEle,"flagdefault",false);
		int defInteger = XMLtools.getIntAttribute(rtvalsEle,"integerdefault",-999);

		readingXML=true;
		if( XMLtools.hasChildByTag(rtvalsEle,"group") ) {
			XMLtools.getChildElements(rtvalsEle, "group").forEach(
					rtval -> {
						// Both id and name are valid attributes for the node name that forms the full id
						var groupName = XMLtools.getStringAttribute(rtval, "id", ""); // get the node id
						groupName = XMLtools.getStringAttribute(rtval, "name", groupName);
						for (var groupie : XMLtools.getChildElements(rtval)) { // Get the nodes inside the group node
							// First check if id is used
							processRtvalElement(groupie, groupName, defReal, defText, defFlag, defInteger);
						}
					}
			);
		}else{ // Rtvals node without group nodes
			XMLtools.getChildElements(rtvalsEle).forEach(
					ele -> processRtvalElement(ele,"",defReal,defText,defFlag,defInteger)
			);
		}

		// Stuff that isn't in a group?
		/*XMLtools.getChildElements(rtvalsEle,"*").stream().filter( rt -> !rt.getTagName().equalsIgnoreCase("group")).forEach(
				rtval -> {
					processRtvalElement(rtval, "", defReal, defText, defFlag,defInteger);
				}
		);*/
		readingXML=false;
	}
	public void readFromXML( XMLfab fab ){
		readFromXML(fab.getCurrentElement());
	}
	/**
	 * Process a Val node
	 * @param rtval The node to process
	 * @param group The group of the Val
	 * @param defReal The default real value
	 * @param defText The default text value
	 * @param defFlag The default boolean value
	 */
	private void processRtvalElement(Element rtval, String group, double defReal, String defText, boolean defFlag, int defInteger ){
		String name = XMLtools.getStringAttribute(rtval,"name","");
		name = XMLtools.getStringAttribute(rtval,"id",name);
		if( name.isEmpty())
			name = rtval.getTextContent();
		String id = group.isEmpty()?name:group+"_"+name;

		switch (rtval.getTagName()) {
			case "double", "real" -> {
				RealVal rv;
				if( !hasReal(id) )
					addRealVal(RealVal.newVal(group, name), false); // create it
				var rvOpt = getRealVal(id);
				if( rvOpt.isEmpty()){
					Logger.error("No such realVal ... but there should be: "+id);
					return;
				}
				rv = rvOpt.get();
				rv.reset(); // reset needed if this is called because of reload
				rv.unit(XMLtools.getStringAttribute(rtval, "unit", ""))
						.scale(XMLtools.getIntAttribute(rtval, "scale", -1))
						.defValue(XMLtools.getDoubleAttribute(rtval, "default", defReal));
				String options = XMLtools.getStringAttribute(rtval, "options", "");
				for (var opt : options.split(",")) {
					var arg = opt.split(":");
					switch (arg[0]) {
						case "minmax" -> rv.keepMinMax();
						case "time" -> rv.keepTime();
						case "scale" -> rv.scale(NumberUtils.toInt(arg[1], -1));
						case "order" -> rv.order(NumberUtils.toInt(arg[1], -1));
						case "history" -> rv.enableHistory(NumberUtils.toInt(arg[1], -1));
						case "abs" -> rv.enableAbs();
					}
				}
				if (!XMLtools.getChildElements(rtval, "cmd").isEmpty())
					rv.enableTriggeredCmds(dQueue);
				for (Element trigCmd : XMLtools.getChildElements(rtval, "cmd")) {
					String trig = trigCmd.getAttribute("when");
					String cmd = trigCmd.getTextContent();
					rv.addTriggeredCmd(trig, cmd);
				}
			}
			case "integer", "int" -> {
				if (!hasInteger(id)) // If it doesn't exist yet
					addIntegerVal(IntegerVal.newVal(group, name), false); // create it
				var iv = getIntegerVal(id).get();//
				iv.reset(); // reset needed if this is called because of reload
				iv.unit(XMLtools.getStringAttribute(rtval, "unit", ""))
						.defValue(XMLtools.getIntAttribute(rtval, "default", defInteger));
				String opts = XMLtools.getStringAttribute(rtval, "options", "");
				for (var opt : opts.split(",")) {
					var arg = opt.split(":");
					switch (arg[0]) {
						case "minmax" -> iv.keepMinMax();
						case "time" -> iv.keepTime();
						case "order" -> iv.order(NumberUtils.toInt(arg[1], -1));
						case "history" -> iv.enableHistory(NumberUtils.toInt(arg[1], -1));
						case "abs" -> iv.enableAbs();
					}
				}
				if (!XMLtools.getChildElements(rtval, "cmd").isEmpty())
					iv.enableTriggeredCmds(dQueue);
				for (Element trigCmd : XMLtools.getChildElements(rtval, "cmd")) {
					String trig = trigCmd.getAttribute("when");
					String cmd = trigCmd.getTextContent();
					iv.addTriggeredCmd(trig, cmd);
				}
			}
			case "text" -> setText(id, XMLtools.getStringAttribute(rtval, "default", defText));
			case "flag" -> {
				var fv = getOrAddFlagVal(id,false);
				fv.reset(); // reset is needed if this is called because of reload
				fv.name(XMLtools.getChildValueByTag(rtval, "name", fv.name()))
						.group(XMLtools.getChildValueByTag(rtval, "group", fv.group()))
						.defState(XMLtools.getBooleanAttribute(rtval, "default", defFlag));
				if (XMLtools.getBooleanAttribute(rtval, "keeptime", false))
					fv.keepTime();
				if (!XMLtools.getChildElements(rtval, "cmd").isEmpty())
					fv.enableTriggeredCmds(dQueue);
				for (Element trigCmd : XMLtools.getChildElements(rtval, "cmd")) {
					String trig = trigCmd.getAttribute("when");
					String cmd = trigCmd.getTextContent();
					fv.addTriggeredCmd(trig, cmd);
				}
			}
		}
	}
	/* ************************************* P A R S I N G ********************************************************* */
	/**
	 * Simple version of the parse realtime line, just checks all the words to see if any matches the hashmaps.
	 * It assumes all words are reals, but also allows for d:id,t:id and f:id
	 * @param line The line to parse
	 * @param error The line to return on an error or 'ignore' if errors should be ignored
	 * @return The (possibly) altered line
	 */
	public String simpleParseRT( String line,String error ){

		var found = words.matcher(line).results().map(MatchResult::group).toList();

		for( var word : found ){
			if( word.contains(":")){ // Check if the word contains a : with means it's {d:id} etc
				var id = word.split(":")[1];
				String repl="";
				switch( word.charAt(0) ) {
					case 'd': case 'r':
						if( hasReal(id) ) { // ID found
							repl = ""+getReal(id,Double.NaN);
						}else{
							Logger.error("No such real "+id+", extracted from "+line); // notify
							if( !error.equalsIgnoreCase("ignore")) // if errors should be ignored
								return error;
						}
						break;
					case 'i':
						if( hasInteger(id) ) { // ID found
							repl = "" + getInteger(id,Integer.MAX_VALUE);
						}else{
							Logger.error("No such integer "+id+", extracted from "+line); // notify
							if( !error.equalsIgnoreCase("ignore")) // if errors should be ignored
								return error;
						}
						break;
					case 'f':
						if( !hasFlag(id)) {
							Logger.error("No such flag "+id+ ", extracted from "+line);
							if( !error.equalsIgnoreCase("ignore"))
								return error;
						}
						repl = isFlagUp(id) ? "1" : "0";
						break;
					case 't': case 'T':
						var te = texts.get(id);
						if( te == null && word.charAt(0)=='T') {
							texts.put(id, "");
							te="";
						}
						if( te !=null ) {
							line = line.replace(word, te);
						}else{
							Logger.error("No such text "+id+", extracted from "+line);
							if( !error.equalsIgnoreCase("ignore"))
								return error;
						}
						break;
				}
				if( !repl.isEmpty() )
					line = line.replace(word,repl);
			}else { // If it doesn't contain : it could be anything...
				if (hasReal(word)) { //first check for real
					line = line.replace(word, "" + getReal(word,Double.NaN));
 				} else { // if not
					if( hasInteger(word)){
						line = line.replace(word, "" + getInteger(word,-999));
					}else {
						if (hasText(word)) { //next, try text
							line = line.replace(word, getText(word,""));
						} else if (hasFlag(word)) { // if it isn't a text, check if it's a flag
							line = line.replace(word, isFlagUp(word) ? "1" : "0");
						} else if (!error.equalsIgnoreCase("ignore")) { // if it's not ignore
							Logger.error("Couldn't process " + word + " found in " + line); // log it and abort
							return error;
						}
					}
				}
			}
		}
		return line;
	}

	/**
	 * Stricter version to parse a realtime line, must contain the references within { }
	 * Options are:
	 * - RealVal: {d:id} and {real:id}
	 * - FlagVal: {f:id} or {b:id} and {flag:id}
	 * This also checks for {utc}/{utclong},{utcshort} to insert current timestamp
	 * @param line The original line to parse/alter
	 * @param error Value to put if the reference isn't found
	 * @return The (possibly) altered line
	 */
	public String parseRTline( String line, String error ){

		if( !line.contains("{"))
			return line;

		var pairs = Tools.parseKeyValue(line,true);
		for( var p : pairs ){
			if(p.length==2) {
				switch (p[0]) {
					case "d", "r", "double", "real" -> {
						var d = getReal(p[1], Double.NaN);
						if (!Double.isNaN(d) || !error.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", Double.isNaN(d) ? error : "" + d);
					}
					case "i", "int", "integer" -> {
						var i = getInteger(p[1], Integer.MAX_VALUE);
						if (i != Integer.MAX_VALUE)
							line = line.replace("{" + p[0] + ":" + p[1] + "}", "" + i);
					}
					case "t", "text" -> {
						String t = getText(p[1], error);
						if (!t.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", t);
					}
					case "f", "b", "flag" -> {
						var d = getFlagVal(p[1]);
						var r = d.map(FlagVal::toString).orElse(error);
						if (!r.isEmpty())
							line = line.replace("{" + p[0] + ":" + p[1] + "}", r);
					}
				}
			}else{
				switch(p[0]){
					case "utc": line = line.replace("{utc}", TimeTools.formatLongUTCNow());break;
					case "utclong": line = line.replace("{utclong}", TimeTools.formatLongUTCNow());
					case "utcshort": line = line.replace("{utcshort}", TimeTools.formatShortUTCNow());
				}
			}
		}
		if( line.toLowerCase().matches(".*[{][drfi]:.*") && !pairs.isEmpty()){
			Logger.warn("Found a {*:*}, might mean parsing a section of "+line+" failed");
		}
		return line;
	}

	/**
	 * Checks the exp for any mentions of the numerical rtvals and if found adds these to the nums arraylist and replaces
	 * the reference with 'i' followed by the index in nums+offset. {D:id} and {F:id} will add the rtvals if they don't
	 * exist yet.

	 * fe. {d:temp}+30, nums still empty and offset 1: will add the RealVal temp to nums and alter exp to i1 + 30
	 * @param exp The expression to check
	 * @param nums The Arraylist to hold the numerical values
	 * @param offset The index offset to apply
	 * @return The altered expression
	 */
	public String buildNumericalMem( String exp, ArrayList<NumericVal> nums, int offset){
		if( nums==null)
			nums = new ArrayList<>();

		// Find all the real/flag pairs
		var pairs = Tools.parseKeyValue(exp,true); // Add those of the format {d:id}
		//pairs.addAll( Tools.parseKeyValueNoBrackets(exp) ); // Add those of the format d:id

		for( var p : pairs ) {
			boolean ok=false;
			if (p.length == 2) {
				for( int pos=0;pos<nums.size();pos++ ){ // go through the known realVals
					var d = nums.get(pos);
					if( d.id().equalsIgnoreCase(p[1])) { // If a match is found
						exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + (offset + pos));
						exp = exp.replace(p[0] + ":" + p[1], "i" + (offset + pos));
						ok=true;
						break;
					}
				}
				if( ok )
					continue;
				int index;
				switch (p[0]) {
					case "d", "double", "r", "real" -> {
						var d = getRealVal(p[1]);
						if (d.isPresent()) {
							index = nums.indexOf(d.get());
							if (index == -1) {
								nums.add(d.get());
								index = nums.size() - 1;
							}
							index += offset;
							exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
						} else {
							Logger.error("Couldn't find a real with id " + p[1]);
							return "";
						}
					}
					case "int", "i" -> {
						var ii = getIntegerVal(p[1]);
						if (ii.isPresent()) {
							index = nums.indexOf(ii.get());
							if (index == -1) {
								nums.add(ii.get());
								index = nums.size() - 1;
							}
							index += offset;
							exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
						} else {
							Logger.error("Couldn't find a integer with id " + p[1]);
							return "";
						}
					}
					case "f", "flag", "b" -> {
						var f = getFlagVal(p[1]);
						if (f.isPresent()) {
							index = nums.indexOf(f.get());
							if (index == -1) {
								nums.add(f.get());
								index = nums.size() - 1;
							}
							index += offset;
							exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
						} else {
							Logger.error("Couldn't find a FlagVal with id " + p[1]);
							return "";
						}
					}
					case "is" -> { // issues
						var i = issuePool.getIssueAsNumerical(p[1]);
						if (i.isPresent()) {
							index = nums.indexOf(i.get());
							if (index == -1) {
								nums.add(i.get());
								index = nums.size() - 1;
							}
							index += offset;
							exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
						} else {
							Logger.error("Couldn't find an Issue with id " + p[1]);
							return "";
						}
					}
					default -> {
						Logger.error("Operation containing unknown pair: " + p[0] + ":" + p[1]);
						return "";
					}
				}
			}else{
				Logger.error( "Pair containing odd amount of elements: "+String.join(":",p));
			}
		}
		// Figure out the rest?
		var found = words.matcher(exp).results().map(MatchResult::group).toList();
		for( String fl : found){
			if( fl.matches("^i\\d+") )
				continue;
			int index;
			if( fl.startsWith("flag:")){
				var f = getFlagVal(fl.substring(5));
				if( f.isPresent() ){
					index = nums.indexOf(f.get());
					if(index==-1){
						nums.add( f.get() );
						index = nums.size()-1;
					}
					index += offset;
					exp = exp.replace(fl, "i" + index);
				}else{
					Logger.error("Couldn't find a FlagVal with id "+fl);
					return "";
				}
			}else{
				var d = getRealVal(fl);
				if( d.isPresent() ){
					index = nums.indexOf(d.get());
					if(index==-1){
						nums.add( d.get() );
						index = nums.size()-1;
					}
					index += offset;
					exp = exp.replace(fl, "i" + index);
				}else{
					var f = getFlagVal(fl);
					if( f.isPresent() ){
						index = nums.indexOf(f.get());
						if(index==-1){
							nums.add( f.get() );
							index = nums.size()-1;
						}
						index += offset;
						exp = exp.replace(fl, "i" + index);
					}else{
						Logger.error("Couldn't find a realval with id "+fl);
						return "";
					}
					return exp;
				}
			}
		}
		nums.trimToSize();
		return exp;
	}
	/**
	 * Look for a numerical val (i.e. RealVal, IntegerVal or FlagVal) with the given id
	 * @param id The id to look for
	 * @return An optional Numericalval that's empty if nothing was found
	 */
	public Optional<NumericVal> getNumericVal( String id){
		if( id.startsWith("{")){ // First check if the id is contained inside a { }
			id = id.substring(1,id.length()-2);
			var pair = id.split(":");
			return switch (pair[0].toLowerCase()) {
				case "i", "int", "integer" -> Optional.ofNullable(integerVals.get(id));
				case "d", "double", "r", "real" -> Optional.ofNullable(realVals.get(id));
				case "f", "flag", "b" -> Optional.ofNullable(flagVals.get(id));
				default -> Optional.empty();
			};
		} // If it isn't inside { } just check if a match is found in the realVals and then the FlagVals
		return getRealVal(id).map(d -> Optional.of((NumericVal)d)).orElse(Optional.ofNullable((flagVals.get(id))));
	}
	/* ************************************ R E A L V A L ***************************************************** */

	/**
	 * Add a RealVal to the collection if it doesn't exist yet, optionally writing it to xml
	 * @param rv The RealVal to add
	 * @param xmlPath The path to the xml
	 * @return True if it was added
	 */
	public boolean addRealVal(RealVal rv, Path xmlPath) {
		if( rv==null) {
			Logger.error("Invalid RealVal received, won't try adding it");
			return false;
		}
		if( realVals.containsKey(rv.getID()))
			return false;

		if(xmlPath!=null&& Files.exists(xmlPath))
			rv.storeInXml(XMLfab.withRoot(xmlPath,"dcafs","rtvals"));
		return realVals.put(rv.getID(),rv)==null;
	}
	@Override
	public boolean addRealVal(RealVal rv, boolean storeInXML) {
		return addRealVal(rv,storeInXML?settingsPath:null);
	}
	/**
	 * Retrieve a RealVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested RealVal or null if not found
	 */
	public Optional<RealVal> getRealVal( String id ){
		if( realVals.get(id)==null)
			Logger.error( "Tried to retrieve non existing realval "+id);
		return Optional.ofNullable(realVals.get(id));
	}

	/**
	 * Rename a real
	 * @param from The old name
	 * @param to The new name
	 * @param alterXml Whether to also alter the xml
	 * @return True if ok
	 */
	public boolean renameReal(String from, String to, boolean alterXml){
		if( hasReal(to) ){
			return false;
		}
		var rv = getRealVal(from);
		if( rv.isPresent() ){
			// Alter the RealVal
			realVals.remove(from); // Remove it from the list
			var name = to;
			if( name.contains("_") )
				name = name.substring(name.indexOf("_")+1);
			String newName = name;
			String oriName = rv.get().name();
			rv.get().name(name);
			realVals.put(to,rv.get()); // Add it renamed

			// Correct the XML
			if( alterXml ) {
				if (name.equalsIgnoreCase(to)) { // so didn't contain a group
					XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("real", "id", from)
							.ifPresent(fab -> fab.attr("id", to).build());
				} else { // If it did contain a group
					var grOpt = XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("group", "id", from.substring(0, from.indexOf("_")));
					if (grOpt.isPresent()) { // If the group tag is already present alter it there
						grOpt.get().selectChildAsParent("real", "name", oriName).ifPresent(f -> f.attr("name", newName).build());
					} else { // If not
						XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("real", "id", from)
								.ifPresent(fab -> fab.attr("id", to).build());
					}
				}
			}
		}
		return true;
	}
	public boolean hasReal(String id){
		return realVals.containsKey(id);
	}
	/**
	 * Sets the value of a real (in a hashmap)
	 * @param id The parameter name
	 * @param value The value of the parameter
	 * @return True if it was created
	 */
	public boolean updateReal(String id, double value) {

		if( id.isEmpty()) {
			Logger.error("Empty id given");
			return false;
		}
		RealVal r = realVals.get(id);
		if( r==null ) {
			Logger.error("No such real "+id+" yet, create it first");
			return false;
		}
		r.updateValue(value);
		return true;
	}
	/**
	 * Alter all the values of the reals in the given group
	 * @param group The group to alter
	 * @param value The value to set
	 * @return The amount of reals updated
	 */
	public int updateRealGroup(String group, double value){
		var set = realVals.values().stream().filter(rv -> rv.group().equalsIgnoreCase(group)).collect(Collectors.toSet());
		set.forEach(rv->rv.updateValue(value));
		return set.size();
	}
	/**
	 * Get the value of a real
	 *
	 * @param id The id to get the value of
	 * @param defVal The value to return of the id wasn't found
	 * @return The value found or the bad value
	 */
	public double getReal(String id, double defVal) {
		var star = id.indexOf("*");
		RealVal d = realVals.get(star==-1?id:id.substring(0,star));
		if (d == null) {
			Logger.error("No such real "+id);
			return defVal;
		}
		if (Double.isNaN(d.value())) {
			Logger.error("ID: " + id + " is NaN.");
			return defVal;
		}
		if( star==-1)
			return d.value();

		return switch( id.substring(star+1) ){
			case "stdev", "stdv"-> d.getStdev();
			case "avg", "average" ->  d.getAvg();
			case "min" -> d.min();
			case "max" -> d.max();
			default -> d.value();
		};
	}
	/* ************************************ I N T E G E R V A L ***************************************************** */

	/**
	 * Retrieve a IntegerVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested IntegerVal or empty optional if not found
	 */
	public Optional<IntegerVal> getIntegerVal( String id ){
		if( integerVals.get(id)==null)
			Logger.error( "Tried to retrieve non existing integerval "+id);
		return Optional.ofNullable(integerVals.get(id));
	}

	/**
	 * Retrieves the id or adds it if it doesn't exist yet
	 * @param iv The IntegerVal to add
	 * @param xmlPath The path of the xml to store it in
	 * @return The requested or newly made integerval
	 */
	public IntegerVal addIntegerVal( IntegerVal iv, Path xmlPath ){
		if( iv==null)
			return null;

		var val = integerVals.get(iv.getID());
		if( !integerVals.containsKey(iv.getID())){
			integerVals.put(iv.getID(),iv);
			if(xmlPath!=null && Files.exists(xmlPath)) {
				iv.storeInXml(XMLfab.withRoot(xmlPath, "dcafs", "rtvals"));
			}else if( xmlPath!=null){
				Logger.error("No such file found: "+xmlPath);
			}
			return iv;
		}
		return val;
	}
	/**
	 * Retrieves the id or adds it if it doesn't exist yet
	 * @param iv The IntegerVal to add
	 * @param xmlStore Whether it needs to be stored in xml
	 * @return The object if found or made or null if something went wrong
	 */
	public IntegerVal addIntegerVal( IntegerVal iv, boolean xmlStore ){
		return addIntegerVal(iv,xmlStore?settingsPath:null);
	}
	public IntegerVal addIntegerVal( String group, String name ){
		return addIntegerVal( IntegerVal.newVal(group,name),settingsPath);
	}

	public boolean renameInteger( String from, String to, boolean alterXml){
		if( hasInteger(to) ){
			return false;
		}
		var iv = getIntegerVal(from);
		if( iv.isPresent() ){
			// Alter the RealVal
			integerVals.remove(from); // Remove it from the list
			var name = to;
			if( name.contains("_") )
				name = name.substring(name.indexOf("_")+1);
			String newName = name;
			String oriName = iv.get().name();
			iv.get().name(name);
			integerVals.put(to,iv.get()); // Add it renamed

			// Correct the XML
			if( alterXml ) {
				if (name.equalsIgnoreCase(to)) { // so didn't contain a group
					XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("int", "id", from)
							.ifPresent(fab -> fab.attr("id", to).build());
				} else { // If it did contain a group
					var grOpt = XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("group", "id", from.substring(0, from.indexOf("_")));
					if (grOpt.isPresent()) { // If the group tag is already present alter it there
						grOpt.get().selectChildAsParent("int", "name", oriName).ifPresent(f -> f.attr("name", newName).build());
					} else { // If not
						XMLfab.withRoot(settingsPath, "dcafs", "rtvals").selectChildAsParent("int", "id", from)
								.ifPresent(fab -> fab.attr("id", to).build());
					}
				}
			}
		}
		return true;
	}
	public boolean hasInteger( String id){
		return integerVals.containsKey(id);
	}
	/**
	 * Sets the value of a parameter (in a hashmap)
	 * @param id The integerval id
	 * @param value the new value
	 * @return True if it was updated
	 */
	public boolean updateInteger(String id, int value) {
		if( !integerVals.containsKey(id))
			return false;
		integerVals.get(id).value(value);
		return true;
	}

	/**
	 * Alter all the values of the ints in the given group
	 * @param group The group to alter
	 * @param value The value to set
	 * @return The amount of ints updated
	 */
	public int updateIntegerGroup(String group, int value){
		var set = integerVals.values().stream().filter( iv -> iv.group().equalsIgnoreCase(group)).collect(Collectors.toSet());
		set.forEach(iv->iv.updateValue(value));
		return set.size();
	}
	/**
	 * Get the value of an integer
	 *
	 * @param id The id to get the value of
	 * @return The value found or the bad value
	 */
	public int getInteger(String id, int defVal) {
		var star = id.indexOf("*");
		IntegerVal i = integerVals.get(star==-1?id:id.substring(0,star));
		if (i == null) {
			Logger.error("No such id: " + id);
			return defVal;
		}

		if( star==-1)
			return i.intValue();

		return switch( id.substring(star+1) ){
			case "min" -> i.min();
			case "max" -> i.max();
			default -> i.intValue();
		};
	}
	/* *********************************** T E X T S  ************************************************************* */
	public boolean hasText(String id){
		return texts.containsKey(id);
	}
	public boolean setText(String parameter, String value) {

		if( parameter.isEmpty()) {
			Logger.error("Empty param given");
			return false;
		}
		Logger.debug("Setting "+parameter+" to "+value);

		boolean created = texts.put(parameter, value)==null;

		if( !textRequest.isEmpty()){
			var res = textRequest.get(parameter);
			if( res != null)
				res.forEach( wr -> wr.writeLine(parameter + " : " + value));
		}
		return created;
	}
	public boolean updateText( String id, String value){
		if( texts.containsKey(id)) {
			texts.put(id, value);
			return true;
		}
		return false;
	}
	public String getText(String parameter, String def) {
		String result = texts.get(parameter);
		return result == null ? def : result;
	}

	/* ************************************** F L A G S ************************************************************* */
	public FlagVal getOrAddFlagVal( String id, boolean storeInXML ){
		return getOrAddFlagVal(id,storeInXML?settingsPath:null);
	}
	public FlagVal addFlagVal( FlagVal fv, Path xmlPath ){
		if( fv==null) {
			Logger.error("Invalid flagval given");
			return null;
		}
		if( !hasFlag(fv.id())){
			flagVals.put(fv.id(),fv);
			if(xmlPath!=null && Files.exists(xmlPath)) {
				fv.storeInXml(XMLfab.withRoot(xmlPath, "dcafs", "rtvals"));
			}else if( xmlPath!=null){
				Logger.error("No such file found: "+xmlPath);
			}
		}
		return getFlagVal(fv.id()).get();
	}
	public FlagVal getOrAddFlagVal( String id, Path xmlPath ){
		if( id.isEmpty())
			return null;

		var val = flagVals.get(id);

		if( val==null){
			val = FlagVal.newVal(id);
			flagVals.put(id,val);

			if( xmlPath!=null && Files.exists(xmlPath) ) { // Only add to xml if not reading from xml
				val.storeInXml(XMLfab.withRoot(xmlPath, "dcafs", "rtvals"));
			}
		}
		return val;
	}
	public Optional<FlagVal> getFlagVal( String flag){
		return Optional.ofNullable(flagVals.get(flag));
	}
	public boolean hasFlag( String flag){
		return flagVals.get(flag)!=null;
	}
	public boolean isFlagUp( String flag ){
		var f = flagVals.get(flag);
		if( f==null) {
			Logger.warn("No such flag: " + flag);
			return false;
		}
		return f.isUp();
	}
	public boolean isFlagDown( String flag ){
		var f = flagVals.get(flag);
		if( f==null) {
			Logger.warn("No such flag: " + flag);
			return false;
		}
		return f.isDown();
	}

	/**
	 * Raises a flag
	 * @param flags The flags/bits to set
	 * @return True if this is a new flag/bit
	 */
	public boolean raiseFlag( String... flags ){
		int cnt = flagVals.size();
		for( var f : flags) {
			getOrAddFlagVal(f,true).setState(true);
		}
		return cnt != flagVals.size();
	}
	/**
	 * Lowers a flag or clears a bool.
	 * @param flag The flags/bits to clear
	 * @return True if this is a new flag/bit
	 */
	public boolean lowerFlag( String... flag ){
		int cnt = flagVals.size();
		for( var f : flag){
			getOrAddFlagVal(f,true).setState(false);
		}
		return cnt!= flagVals.size();
	}
	/**
	 * Set the state of the flag
	 * @param id The flag id
	 * @param state The new state for the flag
	 * @return True if the state was changed, false if it failed
	 */
	public boolean setFlagState( String id, boolean state){

		if(!hasFlag(id)) {
			Logger.error("No such flagVal "+id);
			return false;
		}
		getFlagVal(id).map( fv->fv.setState(state));
		return true;
	}
	public boolean setFlagState( String id, String state){
		if(!hasFlag(id)) {
			Logger.error("No such flagVal "+id);
			return false;
		}
		getFlagVal(id).map( fv->fv.setState(state));
		return true;
	}
	public ArrayList<String> listFlags(){
		return flagVals.entrySet().stream().map(ent -> ent.getKey()+" : "+ent.getValue()).collect(Collectors.toCollection(ArrayList::new));
	}
	/* ********************************* O V E R V I E W *********************************************************** */

	/**
	 * Store the rtvals in the settings.xml
	 * @return The result
	 */
	public boolean storeValsInXml(boolean clearFirst){
		XMLfab fab = XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals");
		if( clearFirst ) // If it needs to cleared first, remove child nodes
			fab.clearChildren();
		var vals = realVals.entrySet().stream().sorted(Entry.comparingByKey()).map(Entry::getValue).toList();
		for( var rv : vals ){
			rv.storeInXml(fab);
		}
		var keys = texts.entrySet().stream().sorted(Entry.comparingByKey()).map(Entry::getKey).toList();
		for( var dt : keys ){
			if( !dt.startsWith("dcafs"))
				fab.selectOrAddChildAsParent("text","id",dt).up();
		}
		var flags = flagVals.entrySet().stream().sorted(Entry.comparingByKey()).map(Entry::getValue).toList();
		for( var fv : flags ){
			fv.storeInXml(fab);
		}
		fab.build();
		return true;
	}
	/* ******************************************************************************************************/

	public int addRequest(Writable writable, String type, String req) {

		switch (type) {
			case "rtval", "double", "real" -> {
				var rv = realVals.get(req);
				if (rv == null)
					return 0;
				rv.addTarget(writable);
				return 1;
			}
			case "int", "integer" -> {
				var iv = integerVals.get(req);
				if (iv == null)
					return 0;
				iv.addTarget(writable);
				return 1;
			}
			case "text" -> {
				var t = textRequest.get(req);
				if (t == null) {
					textRequest.put(req, new ArrayList<>());
					Logger.info("Created new request for: " + req);
				} else {
					Logger.info("Appended existing request to: " + t + "," + req);
				}
				if (!textRequest.get(req).contains(writable)) {
					textRequest.get(req).add(writable);
					return 1;
				}
			}
			default -> Logger.warn("Requested unknown type: " + type);
		}
		return 0;
	}
	public void removeRequests( Writable wr ){
		realVals.values().forEach( rv -> rv.removeTarget(wr));
		integerVals.values().forEach( iv -> iv.removeTarget(wr));
		textRequest.forEach( (k,v) -> v.remove(wr));
	}
	/* ************************** C O M M A N D A B L E ***************************************** */
	@Override
	public String replyToCommand(String[] request, Writable wr, boolean html) {

		switch( request[0] ){
			case "doubles": case "dv": case "rv": case "reals":
				return replyToRealsCmd(request,html);
			case "texts": case "tv":
				return replyToTextsCmd(request,html);
			case "flags": case "fv":
				return replyToFlagsCmd(request,html);
			case "rtval": case "double": case "real": case "int": case "integer":
				int s = addRequest(wr,request[0],request[1]);
				return s!=0?"Request added to "+s+" realvals":"Request failed";
			case "rtvals": case "rvs":
				return replyToRtvalsCmd(request,html);
			case "":
				removeRequests(wr);
				return "";
			default:
				return "unknown command "+request[0]+":"+request[1];
		}
	}
	public boolean removeWritable(Writable writable ) {
		realVals.values().forEach(rv -> rv.removeTarget(writable));
		textRequest.forEach( (key, list) -> list.remove(writable));
		return true;
	}
	public String replyToTextsCmd( String[] request,  boolean html ){

		if( request[1].isEmpty())
			request[1]="list";

		var cmds = request[1].split(",");
		if( cmds.length>3){
			for( int a=3;a<cmds.length;a++){
				cmds[2]+=","+cmds[3];
			}
		}
		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		var join = new StringJoiner(html?"<br>":"\r\n");
		join.setEmptyValue("None yet");
		switch( cmds[0] ){
			/* Info */
			case "?":
				join.add(ora+"Note: both tv and texts are valid starters"+reg)
						.add( cyan+" Create or alter"+reg)
						.add( green+"  tv:new,id,value"+reg+" -> Create a new text (or update) with the given id/value")
						.add( green+"  tv:update,id,value"+reg+" -> Update an existing text, do nothing if not found")
						.add("").add( cyan+" Get info"+reg)
						.add( green+"  tv:?"+reg+" -> Show this message")
						.add( green+"  tv:list"+reg+" -> Get a listing of currently stored texts")
						.add( green+"  tv:reqs"+reg+" -> Get a listing of the requests active");
				return join.toString();
			case "list":
				return String.join(html?"<br>":"\r\n",getRtvalsList(html,false,false,true, true));
			case "reqs":
				textRequest.forEach((rq, list) -> join.add(rq +" -> "+list.size()+" requesters"));
				return join.toString();
			/* Create or alter */
			case "new": case "create":
				if( setText(cmds[1],cmds[2]) )
					return cmds[1]+" stored with value "+cmds[2];
				return cmds[1]+" updated with value "+cmds[2];
			case "update":
				if( updateText(cmds[1],cmds[2]) )
					return cmds[1]+" updated with value "+cmds[2];
				return "No such text found: "+cmds[1];
			default:
				if( updateText( cmds[0],cmds[1] ) ){
					return cmds[0]+" updated with value "+cmds[1];
				}
				return "unknown command: "+request[0]+":"+request[1];
		}
	}
	public String replyToFlagsCmd( String[] request, boolean html ){
		if( request[1].isEmpty())
			request[1]="list";

		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		var cmds = request[1].split(",");
		var join = new StringJoiner(html?"<br>":"\r\n");
		join.setEmptyValue("None yet");

		switch( cmds[0] ){
			case "?":
				join.add(ora+"Note: both fv and flags are valid starters"+reg)
						.add( cyan+" Create or alter"+reg)
						.add( green+"  fv:raise,id"+reg+" or "+green+"flags:set,id"+reg+" -> Raises the flag/Sets the bit, created if new")
						.add( green+"  fv:lower,id"+reg+" or "+green+"flags:clear,id"+reg+" -> Lowers the flag/Clears the bit, created if new")
						.add( green+"  fv:toggle,id"+reg+" -> Toggles the flag/bit, not created if new")
						.add( green+"  fv:match,id,refid"+reg+" -> The state of the flag becomes the same as the ref flag")
						.add( green+"  fv:negated,id,refid"+reg+" -> The state of the flag becomes the opposite of the ref flag")
						.add( green+"  fv:addcmd,id,when:cmd"+reg+" -> Add a cmd with the given trigger to id, current triggers:raised,lowered")
						.add("").add( cyan+" Get info"+reg)
						.add( green+"  fv:list"+reg+" -> Give a listing of all current flags and their state");
				return join.toString();
			case "list":
				return getRtvalsList(html,false,true,false,false);
			case "new": case "add":
				if( cmds.length <2)
					return "Not enough arguments, need flags:new,id<,state> or fv:new,id<,state>";
				getOrAddFlagVal(cmds[1],true).setState(Tools.parseBool( cmds.length==3?cmds[2]:"false",false));
				return "Flag created/updated "+cmds[1];
			case "raise": case "set":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:raise,id or flags:set,id";
				return raiseFlag(cmds[1])?"New flag raised":"Flag raised";
			case "match":
				if( cmds.length < 3 )
					return "Not enough arguments, fv:match,id,targetflag";
				if( !hasFlag(cmds[1]))
					return "No such flag: "+cmds[1];
				if( !hasFlag(cmds[2]))
					return "No such flag: "+cmds[2];
				if( isFlagUp(cmds[2])){
					raiseFlag(cmds[1]);
				}else{
					lowerFlag(cmds[1]);
				}
				return "Flag matched accordingly";
			case "negated":
				if( cmds.length < 3 )
					return "Not enough arguments, fv:negated,id,targetflag";
				if( !hasFlag(cmds[1]))
					return "No such flag: "+cmds[1];
				if( !hasFlag(cmds[2]))
					return "No such flag: "+cmds[2];
				if( isFlagUp(cmds[2])){
					lowerFlag(cmds[1]);
				}else{
					raiseFlag(cmds[1]);
				}
				return "Flag negated accordingly";
			case "lower": case "clear":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:lower,id or flags:clear,id";
				return lowerFlag(cmds[1])?"New flag raised":"Flag raised";
			case "toggle":
				if( cmds.length !=2)
					return "Not enough arguments, need flags:toggle,id";

				if( !hasFlag(cmds[1]) )
					return "No such flag";

				if( isFlagUp(cmds[1])) {
					lowerFlag(cmds[1]);
					return "flag lowered";
				}
				raiseFlag(cmds[1]);
				return "Flag raised";
			case "addcmd":
				if( cmds.length < 3 )
					return "Not enough arguments, fv:addcmd,id,when:cmd";
				var fv = flagVals.get(cmds[1]);
				if( fv==null)
					return "No such real: "+cmds[1];
				String cmd = request[1].substring(request[1].indexOf(":")+1);
				String when = cmds[2].substring(0,cmds[2].indexOf(":"));
				if( !fv.hasTriggeredCmds())
					fv.enableTriggeredCmds(dQueue);
				fv.addTriggeredCmd(when, cmd);
				XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals")
						.selectChildAsParent("flag","id",cmds[1])
						.ifPresent( f -> f.addChild("cmd",cmd).attr("when",when).build());
				return "Cmd added";
			case "update":
				if( cmds.length < 3)
					return "Not enough arguments, fv:update,id,comparison";
				if( !hasFlag(cmds[1]))
					return "No such flag: "+cmds[1];

		}
		return "unknown command "+request[0]+":"+request[1];
	}
	public String replyToRealsCmd(String[] request, boolean html ){
		if( request[1].isEmpty())
			request[1]="list";

		var cmds = request[1].split(",");
		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		double result;
		RealVal rv;
		String p0 = request[0];
		var join = new StringJoiner(html?"<br>":"\r\n");
		switch( cmds[0] ){
			case "?":
				join.add(ora+"Note: both rv and reals are valid starters"+reg)
						.add( cyan+" Create or alter"+reg)
						.add( green+"  "+p0+":new,group,name"+reg+" -> Create a new real with the given group & name")
						.add( green+"  "+p0+":update,id,value"+reg+" -> Update an existing real, do nothing if not found")
						.add( green+"  "+p0+":addcmd,id,when:cmd"+reg+" -> Add a cmd with the given trigger to id")
						.add( green+"  "+p0+":alter,id,param:value"+reg+" -> Alter some of the params of id, currently scale and unit are possible")
						.add( green+"  "+p0+":updategroup,groupid,value"+reg+" -> Update all RealVals that belong to the groupid with the given value")
						.add("").add( cyan+" Get info"+reg)
				  	    .add( green+"  "+p0+":?"+reg+" -> Show this message" )
						.add( green+"  "+p0+":list"+reg+" -> Get a listing of currently stored texts")
						.add( green+"  "+p0+":reqs"+reg+" -> Get a listing of all the requests currently active");
				return join.toString();
			case "list": return getRtvalsList(html,true,false,false, false);
			case "new": case "create":
				if( cmds.length!=3 )
					return "Not enough arguments given, "+request[0]+":new,id(,value)";

				rv = RealVal.newVal(cmds[1], cmds[2]);
				if( addRealVal(rv,true) ) {
					return "New realVal added " + rv.getID() + ", stored in xml";
				}
				return "Real already exists";
			case "alter":
				if( cmds.length<3)
					return "Not enough arguments: "+request[0]+":alter,id,param:value";
				var vals = cmds[2].split(":");
				if( vals.length==1)
					return "Incorrect param:value pair: "+cmds[2];
				var rvOpt = getRealVal(cmds[1]);
				if( rvOpt.isEmpty())
					return "No such real yet.";
				var realVal = rvOpt.get();
				var digger = XMLdigger.digRoot(settingsPath,"dcafs")
						.goDown("settings","rtvals")
						.goDown("group","name",realVal.group())
						.goDown("real","name",realVal.name());
				if( digger.isValid() ){
					switch( vals[0]){
						case "scale":
							realVal.scale(NumberUtils.toInt(vals[1]));
							break;
						case "unit":
							realVal.unit(vals[1]);
							break;
						default:
							return "unknown parameter: "+vals[0];
					}
					digger.alterAttrAndBuild(vals[0],vals[1]);
					return "Altered "+vals[0]+ " to "+ vals[1];
				}else{
					return "No valid nodes found";
				}
			case "update":
				if( !hasReal(cmds[1]) )
					return "No such id "+cmds[1];
				result = processExpression(cmds[2]);
				if( Double.isNaN(result) )
					return "Unknown id(s) in the expression "+cmds[2];
				updateReal(cmds[1],result);
				return cmds[1]+" updated to "+result;
			case "updategroup":
				int up = updateRealGroup(cmds[1], NumberUtils.createDouble(cmds[2]));
				if( up == 0)
					return "No real's updated";
				return "Updated "+up+" reals";
			case "addcmd":
				if( cmds.length < 3)
					return "Not enough arguments, rv:addcmd,id,when:cmd";
				rv = realVals.get(cmds[1]);
				if( rv==null)
					return "No such real: "+cmds[1];
				String cmd = request[1].substring(request[1].indexOf(":")+1);
				String when = cmds[2].substring(0,cmds[2].indexOf(":"));
				if( !rv.hasTriggeredCmds())
					rv.enableTriggeredCmds(dQueue);
				rv.addTriggeredCmd(when,cmd);
				XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals")
						.selectChildAsParent("real","id",cmds[1])
						.ifPresent( f -> f.addChild("cmd",cmd).attr("when",when).build());
				return "Cmd added";
			case "reqs":
				join.setEmptyValue("None yet");
				realVals.forEach((id, d) -> join.add(id +" -> "+d.getTargets()));
				return join.toString();
			default:
				return "unknown command: "+request[0]+":"+request[1];
		}
	}
	private double processExpression( String expr ){
		double result=Double.NaN;

		expr = parseRTline(expr,"");
		expr = expr.replace("true","1");
		expr = expr.replace("false","0");

		expr = simpleParseRT(expr,""); // Replace all references with actual numbers if possible

		if( expr.isEmpty()) // If any part of the conversion failed
			return result;

		var parts = MathUtils.extractParts(expr);
		if( parts.size()==1 ){
			result = NumberUtils.createDouble(expr);
		}else if (parts.size()==3){
			result = Objects.requireNonNull(MathUtils.decodeDoublesOp(parts.get(0), parts.get(2), parts.get(1), 0)).apply(new Double[]{});
		}else{
			try {
				result = MathUtils.simpleCalculation(expr, Double.NaN, false);
			}catch(IndexOutOfBoundsException e){
				Logger.error("Index out of bounds while processing "+expr);
				return Double.NaN;
			}
		}
		return result;
	}
	public String replyToRtvalsCmd( String[] request, boolean html ){

		if( request[1].isEmpty())
			return getRtvalsList(html,true,true,true, true);

		String[] cmds = request[1].split(",");

		String cyan = html?"":TelnetCodes.TEXT_CYAN;
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

		var join = new StringJoiner(html?"<br>":"\r\n");
		join.setEmptyValue("None Yet");

		if( cmds.length==1 ){
			switch( cmds[0]){
				case "?":
					join.add( cyan+" Interact with XML"+reg)
							.add(green+"  rtvals:store"+reg+" -> Store all rtvals to XML")
							.add(green+"  rtvals:reload"+reg+" -> Reload all rtvals from XML")
							.add("").add( cyan+" Get info"+reg)
							.add(green+"  rtvals:?"+reg+" -> Get this message")
							.add(green+"  rtvals"+reg+" -> Get a listing of all rtvals")
							.add(green+"  rtvals:groups"+reg+" -> Get a listing of all the available groups")
							.add(green+"  rtvals:group,groupid"+reg+" -> Get a listing of all rtvals belonging to the group")
							.add(green+"  rtvals:name,valname"+reg+" -> Get a listing of all rtvals with the given valname (independent of group)")
							.add(green+"  rtvals:resetgroup,groupid"+reg+" -> Reset the integers and real rtvals in the given group");
					return join.toString();
				case "store": return  storeValsInXml(false)?"Written in xml":"Failed to write to xml";
				case "reload":
					readFromXML( XMLfab.withRoot(settingsPath,"dcafs","settings","rtvals") );
					return "Reloaded rtvals";
			}
		}else if(cmds.length==2){
			switch(cmds[0]){
				case "group":  return getRTValsGroupList(cmds[1],true,true,true,true,html);
				case "groups":
					String groups = String.join(html?"<br>":"\r\n",getGroups());
					return groups.isEmpty()?"No groups yet":groups;
				case "name"	:  return getAllIDsList(cmds[1],html);
				case "resetgroup":
					int a = updateIntegerGroup(cmds[1],-999);
					a += updateRealGroup(cmds[1],Double.NaN);
					return "Reset "+a+" vals.";
			}
		}
		return "unknown command: "+request[0]+":"+request[1];
	}
	/**
	 * Get a listing of all stored variables that belong to a certain group
	 * @param group The group they should belong to
	 * @param html Use html formatting or telnet
	 * @return The listing
	 */
	public String getRTValsGroupList(String group, boolean showReals, boolean showFlags, boolean showTexts, boolean showInts, boolean html) {
		String eol = html ? "<br>" : "\r\n";
		String title = html ? "<b>Group: " + group + "</b>" : TelnetCodes.TEXT_CYAN + "Group: " + group + TelnetCodes.TEXT_YELLOW;
		String space = "  ";//html ? "  " : "  ";

		StringJoiner join = new StringJoiner(eol, title + eol, "");
		join.setEmptyValue("None yet");
		ArrayList<NumericVal> nums = new ArrayList<>();
		if (showReals){
			nums.addAll(realVals.values().stream().filter(rv -> rv.group().equalsIgnoreCase(group)).toList());
		}
		if( showInts ){
			nums.addAll(integerVals.values().stream().filter(rv -> rv.group().equalsIgnoreCase(group)).toList());
		}
		if( !nums.isEmpty()){
			nums.stream()
					.sorted((nv1, nv2) -> {
						if (nv1.order() != nv2.order()) {
							return Integer.compare(nv1.order(), nv2.order());
						} else {
							return nv1.name().compareTo(nv2.name());
						}
					})
					.map(nv -> {
						if( nv instanceof RealVal)
							return space + nv.name() + " : "+ nv.value()+ nv.unit();
						return space + nv.name() + " : "+ nv.intValue() + nv.unit();
					} ) // change it to strings
					.forEach(join::add);
		}
		if( showTexts ) {
			texts.entrySet().stream().filter(ent -> ent.getKey().startsWith(group + "_"))
					.map(ent -> space + ent.getKey().split("_")[1] + " : " + ent.getValue())
					.sorted().forEach(join::add);
		}
		if( showFlags ) {
			flagVals.values().stream().filter(fv -> fv.group().equalsIgnoreCase(group))
					.map(v -> space + v.name() + " : " + v) //Change it to strings
					.sorted().forEach(join::add); // Then add the sorted the strings
		}
		return join.toString();
	}
	/**
	 * Get a listing of all stored variables that have the given name
	 * @param name The name of the variable or the string the name starts with if ending it with *
	 * @param html Use html formatting or telnet
	 * @return The listing
	 */
	public String getAllIDsList(String name, boolean html) {
		String eol = html?"<br>":"\r\n";
		String title = html?"<b>Name: "+name+"</b>":TelnetCodes.TEXT_CYAN+"Name: "+name+TelnetCodes.TEXT_YELLOW;
		String space = "  ";//html?"  ":"  ";

		StringJoiner join = new StringJoiner(eol,title+eol,"");
		join.setEmptyValue("No matches found");

		String regex;
		if( name.contains("*") && !name.contains(".*")) {
			regex = name.replace("*", ".*");
		}else{
			regex=name;
		}
		realVals.values().stream().filter(rv -> rv.name().matches(regex))
				.forEach(rv -> join.add(space+(rv.group().isEmpty()?"":rv.group()+" -> ")+rv.name()+" : "+rv));
		integerVals.values().stream().filter( iv -> iv.name().matches(regex))
				.forEach(iv -> join.add(space+(iv.group().isEmpty()?"":iv.group()+" -> ")+iv.name()+" : "+iv));
		texts.entrySet().stream().filter(ent -> ent.getKey().matches(regex))
				.forEach( ent -> join.add( space+ent.getKey().replace("_","->")+" : "+ent.getValue()) );
		flagVals.values().stream().filter(fv -> fv.name().matches(regex))
				.forEach(fv -> join.add(space+(fv.group().isEmpty()?"":fv.group()+" -> ")+fv.name()+" : "+fv));
		return join.toString();
	}

	/**
	 * Get the full listing of all reals,flags and text, so both grouped and ungrouped
	 * @param html If true will use html newline etc
	 * @return The listing
	 */
	public String getRtvalsList(boolean html, boolean showReals, boolean showFlags, boolean showTexts, boolean showInts){
		String eol = html?"<br>":"\r\n";
		String space = "  ";//html?"  ":"  ";
		StringJoiner join = new StringJoiner(eol,"Status at "+ TimeTools.formatShortUTCNow()+eol+eol,"");
		join.setEmptyValue("None yet");

		// Find & add the groups
		for( var group : getGroups() ){
			var res = getRTValsGroupList(group,showReals,showFlags,showTexts,showInts,html);
			if( !res.isEmpty() && !res.equalsIgnoreCase("none yet"))
				join.add(res).add("");
		}

		// Add the not grouped ones
		boolean ngReals = realVals.values().stream().anyMatch(rv -> rv.group().isEmpty())&&showReals;
		boolean ngTexts = !texts.isEmpty() && texts.keySet().stream().noneMatch(k -> k.contains("_")) && showTexts;
		boolean ngFlags = flagVals.values().stream().anyMatch( fv -> fv.group().isEmpty())&&showFlags;
		boolean ngIntegers = integerVals.values().stream().anyMatch( iv -> iv.group().isEmpty())&&showInts;

		if( ngReals || ngTexts || ngFlags || ngIntegers ) {
			join.add("");
			join.add(html ? "<b>Default group</b>" : TelnetCodes.TEXT_ORANGE + "Default group" + TelnetCodes.TEXT_YELLOW);

			if (ngReals) {
				join.add(html ? "<b>Reals</b>" : TelnetCodes.TEXT_MAGENTA + "Reals" + TelnetCodes.TEXT_YELLOW);
				realVals.values().stream().filter(rv -> rv.group().isEmpty())
						.map(rv->space + rv.name() + " : " + rv).sorted().forEach(join::add);
			}
			if (ngIntegers){
				join.add(html ? "<b>Integers</b>" : TelnetCodes.TEXT_MAGENTA + "Integers" + TelnetCodes.TEXT_YELLOW);
				integerVals.values().stream().filter(iv -> iv.group().isEmpty())
						.map(iv->space + iv.name() + " : " + iv).sorted().forEach(join::add);
			}
			if (ngTexts) {
				join.add("");
				join.add(html ? "<b>Texts</b>" : TelnetCodes.TEXT_MAGENTA + "Texts" + TelnetCodes.TEXT_YELLOW);
				texts.entrySet().stream().filter(e -> !e.getKey().contains("_"))
						.map( e -> space + e.getKey() + " : " + e.getValue()).sorted().forEach(join::add);
			}
			if (ngFlags) {
				join.add("");
				join.add(html ? "<b>Flags</b>" : TelnetCodes.TEXT_MAGENTA + "Flags" + TelnetCodes.TEXT_YELLOW);
				flagVals.values().stream().filter(fv -> fv.group().isEmpty())
						.map(fv->space + fv.name() + " : " + fv).sorted().forEach(join::add);
			}
		}
		String result = join.toString();
		if( !html)
			return result;

		// Try to fix some symbols to correct html counterpart
		result = result.replace("°C","&#8451"); // fix the °C
		result = result.replace("m²","&#13217;"); // Fix m²
		result = result.replace("m³","&#13221;"); // Fix m³
		return result;
	}

	/**
	 * Get a list of all the groups that exist in the rtvals
	 * @return The list of the groups
	 */
	public List<String> getGroups(){
		var groups = realVals.values().stream()
				.map(RealVal::group)
				.filter(group -> !group.isEmpty()).distinct()
				.collect(Collectors.toList());
		integerVals.values().stream()
				.map(IntegerVal::group)
				.filter(group -> !group.isEmpty() && !groups.contains(group))
				.distinct()
				.forEach(groups::add);
		texts.keySet().stream()
						.filter( k -> k.contains("_"))
						.map( k -> k.split("_")[0] )
						.distinct()
						.filter( g -> !groups.contains(g))
						.filter( g -> !g.equalsIgnoreCase("dcafs"))
						.forEach(groups::add);
		flagVals.values().stream()
						.map(FlagVal::group)
						.filter(group -> !group.isEmpty() )
						.distinct()
						.filter( g -> !groups.contains(g))
						.forEach(groups::add);

		Collections.sort(groups);
		return groups;
	}
	/* ******************************** I S S U E P O O L ********************************************************** */

	/**
	 * Get a list of the id's of the active issues
	 * @return A list of the active issues id's
	 */
	public ArrayList<String> getActiveIssues(){
		return issuePool.getActives();
	}

	/**
	 * Get the IssuePool object
	 * @return The IssuePool object
	 */
	public IssuePool getIssuePool(){
		return issuePool;
	}

	/* ******************************** W A Y P O I N T S *********************************************************** */
	/**
	 * Enable tracking/storing waypoints
	 * @param scheduler Scheduler to use for the tracking
	 * @return The Waypoints object
	 */
	public Waypoints enableWaypoints(ScheduledExecutorService scheduler){
		waypoints = new Waypoints(settingsPath,scheduler,this,dQueue);
		return waypoints;
	}
	/**
	 * Get the waypoints object in an optional, which is empty if it doesn't exist yet
	 * @return The optional that could contain the waypoints object
	 */
	public Optional<Waypoints> getWaypoints(){
		return Optional.ofNullable(waypoints);
	}
}
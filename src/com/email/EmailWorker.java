package com.email;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.concurrent.*;

import javax.activation.CommandMap;
import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.activation.MailcapCommandMap;
import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.search.FlagTerm;

import com.stream.Writable;
import com.stream.collector.BufferCollector;
import com.stream.collector.CollectorFuture;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import util.DeadThreadListener;
import util.xml.XMLfab;
import util.tools.FileTools;
import util.xml.XMLtools;
import util.tools.TimeTools;

import org.tinylog.Logger;

import worker.Datagram;

public class EmailWorker implements Runnable, CollectorFuture {

	static double megaByte = 1024.0 * 1024.0;

	ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	// Email sending settings
	double doZipFromSizeMB = 0.5; // From which attachment size the attachment should be zipped (in MB)
	double maxSizeMB = 1; // Maximum size of attachments to send (in MB)

	/* Queues that hold new emails and retries */
	private final BlockingQueue<EmailWork> emailQueue = new LinkedBlockingQueue<>(); // Queue that holds the emails to send
	private final BlockingQueue<EmailWork> retryQueue = new LinkedBlockingQueue<>(); // Queue holding emails to be
																				// send/retried

	private final Map<String, String> to = new HashMap<>(); // Hashmap containing the reference to email address
																	// 'translation'

	/* Outbox */
	MailBox outbox = new MailBox();

	boolean outboxAuth = false; // Whether or not to authenticate

	/* Inbox */
	MailBox inbox = new MailBox();
	Session inboxSession;

	int errorCount = 0; // Amount of errors encountered
	boolean sendEmails = true; // True if sending emails is allowed
	int delayedResend = 0; // How many delays should be done before trying a resend

	/* Standard SMTP properties */
	Properties props = System.getProperties(); // Properties of the connection
	static final String MAIL_SMTP_CONNECTIONTIMEOUT = "mail.smtp.connectiontimeout";
	static final String MAIL_SOCKET_TIMEOUT = "60000";
	static final String MAIL_SMTP_TIMEOUT = "mail.smtp.timeout";
	static final String MAIL_SMTP_WRITETIMEOUT = "mail.smtp.writetimeout";

	int killThread = 30; // How long to wait (in seconds) before the main thread is killed because
							// unresponsive
	boolean busy = false; // Indicate that an email is being send to the server
	java.util.concurrent.ScheduledFuture<?> retryFuture; // Future of the retry checking thread

	private final BlockingQueue<Datagram> dQueue; // Used to pass commands received via email to the dataworker for
														// processing

	/* Reading emails */
	java.util.concurrent.ScheduledFuture<?> checker; // Future of the inbox checking thread

	int maxQuickChecks = 0; // Counter for counting down how many quick checks for incoming emails are done
							// before slowing down again
	int checkIntervalSeconds = 300; // Email check interval, every 5 minutes (default) the inbox is checked for new
									// emails

	String allowedDomain = ""; // From which domain are emails accepted

	Document xml; // Link to the setting xml
	boolean deleteReceivedZip = true;

	Session mailSession = null;

	long lastInboxConnect = -1; // Keep track of the last timestamp that a inbox connection was made

	protected DeadThreadListener listener;	// a way to notify anything that the thread died somehow

	HashMap<String, DataRequest> buffered = new HashMap<>();	// Store the requests made for data via email

	static final String XML_PARENT_TAG = "email";
	static final String TIMEOUT_MILLIS="10000";

	/**
	 * Constructor for this class
	 * 
	 * @param xml       the xml document containing the settings
	 * @param dQueue the queue processed by a @see BaseWorker
	 */
	public EmailWorker(Document xml, BlockingQueue<Datagram> dQueue) {
		this.dQueue = dQueue;
		this.readSettingsFromXML(xml);
		init();
	}

	/**
	 * Add a listener to be notified of the event the thread fails.
	 * 
	 * @param listener the listener
	 */
	public void setEventListener(DeadThreadListener listener) {
		this.listener = listener;
	}

	/**
	 * Initialises the worker by enabling the retry task and setting the defaults
	 * settings, if reading emails is enabled this starts the thread for this.
	 */
	public void init() {
		setOutboxProps();

		retryFuture = scheduler.scheduleAtFixedRate(new Retry(), 10, 10, TimeUnit.SECONDS); // Start the retry task
		if (dQueue != null) { // No need to check if nothing can be done with it
			if (!inbox.server.isBlank() && !inbox.user.equals("user@email.com")) {
				// Check the inbox every x minutes
				checker = scheduler.scheduleAtFixedRate(new Check(), checkIntervalSeconds,checkIntervalSeconds, TimeUnit.SECONDS);

			}
			inboxSession = Session.getDefaultInstance(new Properties());
			alterInboxProps(inboxSession.getProperties());
		}
		MailcapCommandMap mc = (MailcapCommandMap) CommandMap.getDefaultCommandMap();
		mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html");
		mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml");
		mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain");
		mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed");
		mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822");
		CommandMap.setDefaultCommandMap(mc);	
	}

	/**
	 * Request the queue in which emails are place for sending
	 * 
	 * @return The BlockingQueue to hold the emails to send
	 */
	public BlockingQueue<EmailWork> getQueue(){
		return emailQueue;
	}
	/**
	 * Get the amount of emails in the retry queue, meaning those that need to be send again.
	 * @return Amount of backlog emails
	 */
	public int getRetryQueueSize(){
		return retryQueue.size();
	}
	/**
	 * Checks whether or not EmailWorker info can be found in the settings file.
	 * 
	 * @param xml The XML document maybe containing the settings
	 * @return True if settings are present
	 */
	public static boolean inXML( Document xml){
		return XMLtools.hasElementByTag( xml, XML_PARENT_TAG);	
	}
	/**
	 * Read the settings from the XML file and apply them
	 * 
	 * @param xml The XML document containing the settings
	 */
	public void readSettingsFromXML( Document xml ){
		this.xml=xml;
		Element email = XMLtools.getFirstElementByTag( xml, XML_PARENT_TAG);	// Get the element containing the settings and references

		// Sending
		Element outboxElement = XMLtools.getFirstChildByTag(email, "outbox");
		if( outboxElement != null ){
			Element server = XMLtools.getFirstChildByTag(outboxElement, "server");
			if( server == null){
				Logger.error("No server defined for the outbox");
			}else{
				outbox.setServer( server.getTextContent(), XMLtools.getIntAttribute(server,"port",25) );			// The SMTP server
				outbox.setLogin( XMLtools.getStringAttribute(server, "user", ""), XMLtools.getStringAttribute( server, "pass", "" ));
				outbox.hasSSL = XMLtools.getBooleanAttribute( server, "ssl",  false);
				outbox.from = XMLtools.getChildValueByTag( outboxElement, "from", "das@email.com" );	// From emailaddress

				doZipFromSizeMB = XMLtools.getChildDoubleValueByTag( outboxElement, "zip_from_size_mb", 10);		// Max unzipped filesize
				deleteReceivedZip = XMLtools.getChildBooleanValueByTag( outboxElement, "delete_rec_zip", true);	// Delete received zip files after unzipping
				maxSizeMB = XMLtools.getChildDoubleValueByTag( outboxElement, "max_size_mb", 15.0);				// Max zipped filesize to send
			}
		}
		
		Element inboxElement = XMLtools.getFirstChildByTag(email, "inbox");
		if( inboxElement != null ){
			Element server = XMLtools.getFirstChildByTag(inboxElement, "server");
			if( server == null){
				Logger.error("No server defined for the inbox");
			}else {
				inbox.setServer( server.getTextContent(), XMLtools.getIntAttribute(server,"port",993) );
				inbox.setLogin( XMLtools.getStringAttribute(server, "user", ""), XMLtools.getStringAttribute(server, "pass", ""));
				inbox.hasSSL = XMLtools.getBooleanAttribute( server, "ssl",  false);

				String interval = XMLtools.getChildValueByTag(email, "checkinterval", "5m");    // Interval to check for new emails (in minutes)
				checkIntervalSeconds = TimeTools.parsePeriodStringToSeconds(interval);
				allowedDomain = XMLtools.getChildValueByTag(email, "allowed", "");
			}
		}			
		
		/*
		*  Now figure out the various references used, linking keywords to emailaddresses
		**/
		readEmailBook(email);
	}

	/**
	 * Reads the content of the xml element containing the id -> email references
	 * @param email The element containing the email info
	 */
	private void readEmailBook( Element email ){
		to.clear();  // Clear previous references
		Element book = XMLtools.getFirstChildByTag(email, "book");
		for( Element entry : XMLtools.getChildElements( book, "entry" ) ){
			String addresses = entry.getTextContent();
			String ref = XMLtools.getStringAttribute(entry, "ref", "");
			if( !ref.isBlank() && !addresses.isBlank() ){
				addTo(ref, addresses);
			}else{
				Logger.warn("email book entry has empty ref or address");
			}			
		}
	}
	/**
	 * This creates the barebones settings in the xml
	 * 
	 * @param xmlDoc The original XML file to alter
	 * @param sendEmails Whether or not to include sending emails
	 * @param receiveEmails Whether or not to include checking for emails
	 * @return True if changes were written to the xml
	 */
	public static boolean addBlankEmailToXML( Document xmlDoc, boolean sendEmails, boolean receiveEmails ){	
		
		if( XMLtools.hasElementByTag( xmlDoc,XML_PARENT_TAG) )//Don't overwrite if already exists?
			return false;

		XMLfab fab = XMLfab.withRoot(xmlDoc, "settings",XML_PARENT_TAG);
		if( sendEmails ){
			fab.addParent("outbox","Settings related to sending")
					 .addChild("server", "host/ip").attr("user").attr("pass").attr("ssl","yes").attr("port","993")
					  .addChild("from","das@email.com")
					  .addChild("zip_from_size_mb","3")
					  .addChild("delete_rec_zip","yes")
					  .addChild("max_size_mb","10");
		}
		if( receiveEmails ){	
			fab.addParent("inbox","Settings for receiving emails")
					   .addChild("server", "host/ip").attr("user").attr("pass").attr("ssl","yes").attr("port","465")
					   .addChild("checkinterval","5m");
		}
		fab.addParent("book","Add entries to the emailbook below")
				.addChild("entry","admin@email.com").attr("ref","admin");
		fab.build();
		return true;
	}
	/**
	 * Alter the settings.xml based on the current settings
	 * 
	 * @param xmlDoc The document to change
	 * @return True if no errors occurred
	 */
	public boolean updateSettingsInXML( Document xmlDoc ){
		   
		Element root = XMLtools.getFirstElementByTag( xmlDoc, "settings" );
		Element emailbook = XMLtools.getFirstChildByTag( root,"email" );
		Element outboxElement = XMLtools.getFirstChildByTag(emailbook, "outbox");
		if( outboxElement != null ){
			String ser = XMLtools.getChildValueByTag( outboxElement, "server", "" );
			Logger.info("Server changed, from "+ser+" to "+outbox.server);
			Element server = XMLtools.getFirstChildByTag(outboxElement, "server");
			if( server != null ){
				server.setTextContent(outbox.server);
				server.setAttribute( "user", outbox.user);
				server.setAttribute( "pass", outbox.pass);
				server.setAttribute( "ssl", outbox.hasSSL?"yes":"no" );
			}
		}
		Element inboxElement = XMLtools.getFirstChildByTag(emailbook, "inbox");
		if( inboxElement != null && !inbox.server.isBlank() ){
			Element server = XMLtools.getFirstChildByTag(inboxElement, "server");
			if( server != null ){
				server.setTextContent(inbox.server);
				server.setAttribute( "user", inbox.user);
				server.setAttribute( "pass", inbox.pass);
				server.setAttribute( "ssl", inbox.hasSSL?"yes":"no" );
			}
		}

		return XMLtools.updateXML( xmlDoc );//overwrite the file
	}
	/**
	 * Add an email address to an id
	 * 
	 * @param id The id to add address to
	 * @param email The email address to add to the id
	 */
	public void addTo(String id, String email){
		String old = to.get(id);				// Get any emailadresses already linked with the id, if any
		email = email.replace(";", ",");		// Multiple emailaddresses should be separated with a colon, so alter any semi-colons
		if( old != null && !old.isBlank()){	// If an emailadres was already linked, add the new one(s) to it
			to.put(id, old+","+email);
		}else{									// If not, put the new one
			to.put(id, email);
		}		
		Logger.info( "Set "+id+" to "+to.get(id)); // Add this addition to the status log
	}
	/**
	 * Request the content of the 'emailbook'
	 * @return Listing of all the emails and references in the emailbook
	 */
	public String getEmailBook( ){
		StringJoiner b = new StringJoiner( "\r\n", "-Emailbook-\r\n", "");		
		for( Map.Entry<String,String> ele : to.entrySet()){
			b.add(ele.getKey()+" -> "+ele.getValue() );
		}
		return b.toString();
	}
	/**
	 * Gets the settings used by the EmailWorker
	 * 
	 * @return Listing of all the current settings as a String
	 */
	public String getSettings(){
		StringJoiner b = new StringJoiner( "\r\n","--Email settings--\r\n","\r\n");
		b.add("-Sending-");
		b.add("Server: "+outbox.server+":"+outbox.port);
		b.add("SSL: "+outbox.hasSSL);
		b.add("From (send replies): "+outbox.from);
		b.add("Attachments zip size:"+doZipFromSizeMB);
		b.add("Maximum attachment size:"+maxSizeMB);
	
		b.add("").add("-Receiving-");
		b.add("Inbox: "+inbox.server+":"+inbox.port);
		if( checker == null || checker.isCancelled() || checker.isDone()){
			b.add( "Next inbox check in: Never,checker stopped");
		}else{
			b.add("Inbox check in: "
					+TimeTools.convertPeriodtoString(checker.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS)
					+"/"
					+TimeTools.convertPeriodtoString(checkIntervalSeconds, TimeUnit.SECONDS)
					+" Last ok: "+getTimeSincelastInboxConnection());
		}
		
		b.add("User: "+inbox.user);
		b.add("SSL: "+inbox.hasSSL);
		b.add("Allowed: " + allowedDomain );
		return b.toString();
	}
	public String getTimeSincelastInboxConnection(){
		if( lastInboxConnect == -1 )
			return "never";
		return TimeTools.convertPeriodtoString( Instant.now().toEpochMilli() - lastInboxConnect, TimeUnit.MILLISECONDS)+" ago";	
	}
	/**
	 * Set the SMTP server
	 * 
	 * @param server Address to the server (either hostname or ip)
	 */
	public void setOutboxServer(String server){
		this.outbox.server = server;
		setOutboxProps();
	}
	/**
	 * Get the hostname or ip of the server
	 * 
	 * @return Hostname or ip of the server
	 */
	public String getOutboxServer(){
		return outbox.server+":"+outbox.port;
	}
	/**
	 * Enable or disable sending emails
	 * 
	 * @param send True if enabled
	 */
	public void setSending( boolean send ){
		sendEmails = send;
	}
	/**
	 * Set the name of the user for the inbox
	 * 
	 * @param user Name used to log in the inbox
	 */
	public void setInboxUser( String user ){
		inbox.user=user;
	}
	/**
	 * Set the password for the inbox
	 * 
	 * @param pass The password to access the inbox
	 */
	public void setInboxPass( String pass ){
		inbox.pass=pass;
	}
	/**
	 * Get the password to access the inbox
	 * 
	 * @return The password to access the inbox
	 */
	public String getInboxPass(){
		return inbox.pass;
	}
	/**
	 * Set the properties for sending emails
	 */
	private void setOutboxProps(){
		if( !outbox.server.equals("")){
			props.setProperty("mail.host", outbox.server);
			props.setProperty("mail.transport.protocol", "smtp");

			props.put("mail.smtp.port", outbox.port );

			if( outbox.hasSSL ){
				props.put("mail.smtp.ssl.enable", "true");				
			}
			
			if( !outbox.pass.isBlank() || !outbox.user.isBlank() ){
				props.put("mail.smtp.auth", "true");
				props.put("mail.smtp.user", outbox.user);
				outboxAuth = true;
			}

			// Set a fixed timeout of 60s for all operations the default timeout is "infinite"
			props.put(MAIL_SMTP_CONNECTIONTIMEOUT, MAIL_SOCKET_TIMEOUT);
			props.put(MAIL_SMTP_TIMEOUT, MAIL_SOCKET_TIMEOUT);
			props.put(MAIL_SMTP_WRITETIMEOUT, MAIL_SOCKET_TIMEOUT);
		}
	}
	/**
	 * Main worker thread that sends emails
	 */
	@Override
	public void run() {
		Thread.currentThread().setContextClassLoader( getClass().getClassLoader()    );
		Thread.currentThread().setContextClassLoader( Session.class.getClassLoader() );
		
		while( !Thread.currentThread().isInterrupted() ){
			try {
				TimeUnit.MILLISECONDS.sleep(200); //sleep a fifth of a second between sending emails
				EmailWork email = emailQueue.take(); // Always retrieve emails, whether or not they'll be send.
				String attach="";
				if( !email.isValid()){
					Logger.error( "Invalid email, skipping.");
					continue;
				}
				String too = email.toRaw;
				if(!too.contains("@")){
					String rep = to.get(too);
					if( rep != null && !rep.isBlank()){
						emailQueue.add(new EmailWork(rep,email.subject,email.content,email.attachment,email.deleteAttachment));
						Logger.info( "Converted "+too+" to "+rep);
					}else{
						Logger.info( "Keyword received ("+too+") but no emails attached.");
					}
					continue;
				}
				Message message;
		    	try {
		            if( sendEmails ){ // If sending emails is enabled, can be disabled for debugging						
						if( mailSession == null ){
							if( outboxAuth ){
								mailSession = Session.getInstance(props, new javax.mail.Authenticator() {
									@Override
									protected PasswordAuthentication getPasswordAuthentication() {
										return new PasswordAuthentication(outbox.user, outbox.pass);
									}
								});
							}else{
								mailSession = javax.mail.Session.getInstance(props, null);
							}
							//mailSession.setDebug(true); 	// No need for extra feedback
						}
			            						
						message = new MimeMessage(mailSession);

			            String subject = email.subject;
			            if( email.subject.endsWith(" at.")){ //macro to get the local time added 
			            	subject = email.subject.replace(" at.", " at "+TimeTools.formatNow("HH:mm") +".");						
						}
			            message.setSubject(subject);
			            if( !email.hasAttachment() ){ // If there's no attachment, this changes the content type
			            	message.setContent(email.content, "text/html");
			            }else{
			            	int a = email.attachment.indexOf("["); 
							// If a [ ... ] is present this means that a datetime format is enclosed and then [...] will be replaced
							// with format replaced with actual current datetime
							// eg [HH:mm] -> 16:00
							if( a != -1 ){ 
			            		int b = email.attachment.indexOf("]");
			            		String dt = email.attachment.substring(a+1, b);
			            		dt = TimeTools.formatUTCNow(dt); //replace the format with datetime
			            		attach = email.attachment.substring(0,a) + dt + email.attachment.substring(b+1); // replace it in the attachment
			            		Logger.info( "Changed "+email.attachment+ " to "+attach);
			            	}else{ // Nothing special happens
			            		attach = email.attachment;
			            	}														

							try{
								Path path = Paths.get(attach);
								if( Files.notExists( path ) ){ // If the attachment doesn't exist
									email.attachment="";
									message.setContent(email.content, "text/html"); 
									message.setSubject(subject+" [attachment not found!]"); // Notify the receiver that is should have had an attachment
								}else if( Files.size(path) > doZipFromSizeMB * megaByte ) { // If the attachment is larger than the zip limit
									FileTools.zipFile( path ); // zip it
									attach += ".zip"; // rename attachment
									Logger.info( "File zipped because of size larger than "+doZipFromSizeMB+"MB. Zipped size:"+Files.size(path)/megaByte+"MB");
									path = Paths.get( attach );// Changed the file to archive, zo replace file
									if( Files.size(path)  > maxSizeMB * megaByte ) { // If the zip file it to large to send, maybe figure out way to split?
										email.attachment="";
										message.setContent(email.content, "text/html");
										message.setSubject(subject+" [ATTACHMENT REMOVED because size constraint!]");
										Logger.info( "Removed attachment because to big (>"+maxSizeMB+"MB)");
									}
			            		}
							}catch( IOException e){
								Logger.error(e);
							}							
			            }
			            message.setFrom( new InternetAddress("DAS <"+outbox.from+">") );
			            for( String single : email.toRaw.split(",") ) {			            	
			            	try {
			            		message.addRecipient( Message.RecipientType.TO, new InternetAddress(single.split("\\|")[0]) );
			            	} catch (AddressException e) {
			        			Logger.warn( "Issue trying to convert: "+single.split("\\|")[0] + "\t"+e.getMessage() );
			        			Logger.error(e.getMessage());
			        		}		
			            }
			            //Add attachment
			            if( email.hasAttachment() ){
				            // Create the message part 
				            BodyPart messageBodyPart = new MimeBodyPart();
	
				            // Fill the message
				            messageBodyPart.setContent(email.content, "text/html");
				            
				            // Create a multipar message
				            Multipart multipart = new MimeMultipart();
	
				            // Set text message part
				            multipart.addBodyPart(messageBodyPart);
	
				            // Part two is attachment			            			            			          
				            messageBodyPart = new MimeBodyPart();
				            DataSource source = new FileDataSource(attach);
				            messageBodyPart.setDataHandler( new DataHandler(source) );
				            messageBodyPart.setFileName( Paths.get(attach).getFileName().toString() );
				            multipart.addBodyPart(messageBodyPart);
				            
				            message.setContent(multipart );
			            }
			            // Send the complete message parts
			            Logger.debug( "Trying to send email to "+email.toRaw+" through "+outbox.server+"!");
			            busy = true;
			            Transport.send( message );
			            busy = false;
			         
			            if( attach.endsWith(".zip")) { // If a zip was made, remove it afterwards
			            	try {
								Files.deleteIfExists(Paths.get(attach));
							} catch (IOException e) {
								Logger.error(e);
							}
			            }
			            
		            	if( email.deleteOnSend() ){ 
		            		try {
								Files.deleteIfExists(Paths.get(email.attachment));
							} catch (IOException e) {
								Logger.error(e);
							}
		            	}
		            }else{
		            	Logger.warn( "Sending emails disabled!");
		            }
					errorCount = 0;
					while( !retryQueue.isEmpty() ) 	// If this emails went through, do the retries asap (on a 10s interval)
						emailQueue.add( retryQueue.take() );
					
		        } catch (MessagingException ex) {
					Logger.error( "Failed to send email: "+ex );					
		            email.addAttempt();
		            Logger.info( "Adding email to "+email.toRaw+" about "+email.subject+" to resend queue. Errorcount: "+errorCount );
					retryQueue.add(email);	
					if( retryFuture == null || retryFuture.isDone() || retryFuture.isCancelled()  ){ // If it got stopped somehow, restart it
						retryFuture = scheduler.scheduleAtFixedRate(new Retry(), 10, 10, TimeUnit.SECONDS);
					}
					if( delayedResend < email.getAttempts() ) //wait depending on the amount of attempts if longer than current delay        
		            	delayedResend = email.getAttempts();
		            errorCount++;
		        }
			} catch (InterruptedException e) {
				Logger.error(e);
			}	
		}
		listener.notifyCancelled("EmailWorker");
		Logger.error( "Email thread stopped for some reason..." );
	}
	/**
	 * Stop the worker (for debugging purposes)
	 */
	public void stopWorker(){
		Thread.currentThread().interrupt();
	}
	/**
	 * Checks the emailbook to see to which reference the emailadres is linked
	 * 
	 * @param email The email address to look for
	 * @return Semi-colon (;) separated string with the found refs
	 */
	public String getEmailRefs( String email ){
		StringJoiner b = new StringJoiner( ";");

		to.entrySet().stream().filter( set -> set.getValue().contains(email)) // only retain the refs that have the email
							  .forEach( set -> b.add(set.getKey()));
		
		return b.toString();
	}
	/**
	 * Checks if a certain email address is part of the list associated with a certain ref
	 * @param ref The reference to check
	 * @param address The emailaddress to look for
	 * @return True if found
	 */
	public boolean isAddressInRef( String ref, String address ){
		String list = to.get(ref);
		return list.contains(address);
	}
	/**
	 * Method to have the worker act on commands/requests
	 * 
	 * @param req The command/request to execute
	 * @param html Whether or not the response should be html formatted
	 * @return The response to the command/request
	 */
	public String replyToSingleRequest( String req, boolean html ){

        String[] parts = req.split(",");
        
        switch(parts[0]){
			case "?":
				StringJoiner b = new StringJoiner(html?"<br>":"\r\n");
				b.add("email:reload -> Reload the settings found in te XML.");
				b.add("email:refs -> Get a list of refs and emailadresses.");
				b.add("email:setup -> Get a listing of all the settings.");
				b.add("email:checknow -> Checks the inbox for new emails");
				b.add("email:interval,x -> Change the inbox check interval to x");
				return b.toString();
			case "reload": 
				if( xml == null )
					return "No xml defined yet...";
				readSettingsFromXML(xml);
				return "Settings reloaded";
			case "refs": return this.getEmailBook();
			case "setup":case "status": return this.getSettings();
			case "send":
				if( parts.length !=4 )
					return "Not enough arguments send,ref/email,subject,content";
				sendEmail(parts[1],parts[2],parts[3]);
				return "Tried to send email";
			case "checknow":
				checker.cancel(false);
				checker = scheduler.schedule( new Check(), 1, TimeUnit.SECONDS);
				return "Will check emails asap.";
			case "interval":
				if( parts.length==2){
					this.checkIntervalSeconds = TimeTools.parsePeriodStringToSeconds(parts[1]);
					return "Interval changed to "+this.checkIntervalSeconds+" seconds (todo:save to settings.xml)";
				}else{
					return "Invalid number of parameters";
				}
			default	:
				return "unknown command";
		}
	}
	/**
	 * Simple way of adding email to the queue
	 * 
	 * @param to Emailaddress or reference
	 * @param subject The subject of the email
	 * @param content The content of the email
	 */
	public void sendEmail( String to, String subject, String content ){
		emailQueue.add( new EmailWork( to, subject, content) );
		
	}
	/**
	 * Simple way of adding email to the queue
	 * 
	 * @param to Emailaddress or reference
	 * @param subject The subject of the email
	 * @param content The content of the email
	 * @param attachment The path to the attachment
	 * @param deleteAttachment Whether or not to delete attachment after send was ok
	 */
	public void sendEmail( String to, String subject, String content,String attachment,boolean deleteAttachment ){
		emailQueue.add( new EmailWork( to, subject, content,attachment,deleteAttachment) );
		
	}
	/* *********************************  W O R K E R S ******************************************************* */

	/**
	 * Class that retries to send emails if they failed
	 */
	public class Retry implements Runnable {
		@Override
		public void run() {
			if( delayedResend > 0){
				delayedResend --;
			}
			if( delayedResend == 0 && !retryQueue.isEmpty()){ // If the delay passed and the queue isn't empty
				try {
					emailQueue.add( retryQueue.take() ); // Take an element from the retryqueue and add it to the sending queue
				} catch (InterruptedException e) {
					Logger.error( "Failed to move emails from queue to queue", true);
				}
			}
			// Has nothing to do with resending, but might also use the timed task instead of making a separate one
			if( busy ){
				killThread --;
				if( killThread == 0 ){ //Try to kill the thread after 5min of inactivity
					Thread.currentThread().interrupt();
				}
			}else{
				killThread = 30;
			}
		}
	}
	/**
	 * Set the properties for sending emails
	 */
	private void alterInboxProps( Properties props ){
		// Set a fixed timeout of 10s for all operations the default timeout is "infinite"		
		props.put( "mail.imap.connectiontimeout", TIMEOUT_MILLIS); //10s timeout on connection
		props.put( "mail.imap.timeout", TIMEOUT_MILLIS);
		props.put( "mail.imap.writetimeout", TIMEOUT_MILLIS);
		props.put( MAIL_SMTP_TIMEOUT, TIMEOUT_MILLIS);    
		props.put( MAIL_SMTP_CONNECTIONTIMEOUT, TIMEOUT_MILLIS);
	}
	/**
	 * Class that checks for emails at a set interval.
	 */
	public class Check implements Runnable {

		@Override
		public void run() {
			ClassLoader tcl = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(javax.mail.Session.class.getClassLoader());
			boolean ok = false;

			try( Store inboxStore = inboxSession.getStore("imaps")) {	// Store implements autoCloseable

				inboxStore.connect( inbox.server, inbox.port, inbox.user, inbox.pass );
				
			    Folder inbox = inboxStore.getFolder( "INBOX" );
			    inbox.open( Folder.READ_WRITE );				
			    // Fetch unseen messages from inbox folder
			    Message[] messages = inbox.search(
			        new FlagTerm(new Flags(Flags.Flag.SEEN), false));

			    if( messages.length >0 ){
					Logger.info("Messages found:"+messages.length);
					maxQuickChecks = 5;
				}

			    for ( Message message : messages ) {					
			    	
					String from = message.getFrom()[0].toString();
					from = from.substring(from.indexOf("<")+1, from.length()-1);
					Logger.info("Command: " + message.getSubject() + " from: " + from );

					if ( message.getContentType()!=null && message.getContentType().contains("multipart") ) {
						try {
							Object objRef = message.getContent();
							if(objRef instanceof Multipart){

								Multipart multiPart = (Multipart) message.getContent();
								if( multiPart != null ){
									for (int i = 0; i < multiPart.getCount(); i++) {
										MimeBodyPart part = (MimeBodyPart) multiPart.getBodyPart(i);

										if ( part != null && Part.ATTACHMENT.equalsIgnoreCase( part.getDisposition() )) {
											Logger.info("Attachment found:"+part.getFileName());
											Path p = Paths.get("attachments",part.getFileName());
											Files.createDirectories(p.getParent());

											part.saveFile(p.toFile());
											
											if( p.getFileName().toString().endsWith(".zip")){
												Logger.info("Attachment zipped!");
												FileTools.unzipFile( p.toString(), "attachments" );
												Logger.info("Attachment unzipped!");
												if( deleteReceivedZip )
													Files.deleteIfExists(p);
											}
										}
									}
								}
							}else{
								Logger.error("Can't work with this thing: "+message.getContentType());
							}
						} catch (Exception e) {
							Logger.error("Failed to read attachment");
							Logger.error(e);
						}
					}
					
					if( from.endsWith(allowedDomain)) {
						String cmd = message.getSubject();
						String body = getTextFromMessage(message);

						if( cmd.startsWith("label:")&&cmd.length()>7){ // email acts as data received from sensor, no clue on the use case yet
							for( String line : body.split("\r\n") )
								dQueue.add( new Datagram( body, 1, cmd.split(":")[1]));
						}else{
							// Retrieve asks files to be emailed, if this command is without email append from address
							if( cmd.startsWith("retrieve:") && cmd.split(",").length!=2 ){
								cmd += ","+from;
							}
							Datagram d = new Datagram( cmd, 1, "email");
							DataRequest req = new DataRequest(from,cmd);
							d.setWritable(req.getWritable());
							buffered.put(req.getID(), req);
							d.setOriginID(from);
							dQueue.add( d );
						}
			    	}else {
			    		Logger.warn("Received spam from: "+from);
					}
					message.setFlag(Flags.Flag.DELETED, true);
				}
				ok=true;
				lastInboxConnect = Instant.now().toEpochMilli();							    		
			}catch(com.sun.mail.util.MailConnectException  f ){	
				Logger.error("Couldn't connect to host: "+inbox.server);
			} catch ( MessagingException | IOException e1) {
				Logger.error(e1.getMessage());
			}catch(RuntimeException e){
				Logger.error("Runtime caught");
				Logger.error(e);
			}finally{
				Thread.currentThread().setContextClassLoader(tcl);						
			}
			
			// If an email was received, half the interval or reduce it to 60s. This way follow ups are responded to quicker
			if( !ok ){
				Logger.warn("Failed to connect to inbox, retry scheduled. (last ok: "+getTimeSincelastInboxConnection()+")");
				scheduler.schedule( new Check(), 60, TimeUnit.SECONDS);
			}else if( maxQuickChecks > 0){
				scheduler.schedule( new Check(), Math.min(checkIntervalSeconds/3, 60), TimeUnit.SECONDS);
			}
	   }
	}
	/**
	 * Retrieve the text content of an email message
	 * @param message The message to get content from
	 * @return The text content if any, delimited with \r\n
	 * @throws MessagingException
	 * @throws IOException
	 */
	private String getTextFromMessage(Message message) throws MessagingException, IOException {
		String result = "";
		if (message.isMimeType("text/plain")) {
			result = message.getContent().toString();
		} else if (message.isMimeType("multipart/*")) {
			MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
			result = getTextFromMimeMultipart(mimeMultipart);
		}
		return result;
	}

	/**
	 * Retrieve the text from a mimemultipart
	 * @param mimeMultipart The part to get the text from
	 * @return The text found
	 * @throws MessagingException
	 * @throws IOException
	 */
	private String getTextFromMimeMultipart(
			MimeMultipart mimeMultipart)  throws MessagingException, IOException{

		StringJoiner result = new StringJoiner("\r\n");
		int count = mimeMultipart.getCount();
		for (int i = 0; i < count; i++) {
			BodyPart bodyPart = mimeMultipart.getBodyPart(i);
			if (bodyPart.isMimeType("text/plain")) {
				result.add(""+bodyPart.getContent());
				break; // without break same text appears twice in my tests
			} else if (bodyPart.isMimeType("text/html")) {
				String html = (String) bodyPart.getContent();
				result.add(html); // maybe at some point do actual parsing?
			} else if (bodyPart.getContent() instanceof MimeMultipart){
				result.add( getTextFromMimeMultipart((MimeMultipart)bodyPart.getContent()));
			}
		}
		return result.toString();
	}
	@Override
	public void collectorFinished( String id, String message, Object result) {

		DataRequest req = buffered.remove(id.split(":")[1]);
		if( req == null ){
			Logger.error("No such DataRequest: "+id);
			return;
		}

		if( req.getBuffer().isEmpty() ){
			Logger.error("Buffer returned without info");
		}else{
			Logger.info("Buffer returned with info");
			sendEmail(req.to, "Buffered response to "+req.about, String.join("<br>", req.getBuffer()));
		}
	}

	/**
	 * Test if the BufferCollector construction wroks
	 */
	public void testCollector(){
		Datagram d = new Datagram( "calc:clock", 1, "email");
		DataRequest req = new DataRequest("admin","calc:clock");
		buffered.put(req.getID(), req);
		d.setWritable(req.getWritable());
		buffered.put(req.getID(), req);
		d.setOriginID("admin");
		dQueue.add( d );
	}
	public class DataRequest{
		BufferCollector bwr;
		String to;
		String about;

		public DataRequest( String to, String about ){
			bwr = BufferCollector.timeLimited(""+Instant.now().toEpochMilli(), "60s", scheduler);
			bwr.addListener(EmailWorker.this);
			this.to=to;
			this.about=about;
		}
		public String getID(){
			return bwr.getID();
		}
		public List<String> getBuffer(){
			return bwr.getBuffer();
		}
		public Writable getWritable(){
			return bwr.getWritable();
		}
	}
	private class MailBox{
		String server = ""; // Server to send emails with
		int port = -1; // Port to contact the server on
		boolean hasSSL = false; // Whether or not the outbox uses ssl
		String user = ""; // User for the outbox
		String pass = ""; // Password for the outbox user
		boolean auth = false; // Whether or not to authenticate
		String from = "das"; // The email address to use as from address

		public void setServer(String server, int port){
			this.port = port;
			this.server=server;
		}
		public void setLogin( String user, String pass ){
			this.user=user;
			this.pass=pass;
		}
	}
}

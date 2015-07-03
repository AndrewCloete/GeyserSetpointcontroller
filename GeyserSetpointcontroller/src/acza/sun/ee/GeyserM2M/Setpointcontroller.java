/* --------------------------------------------------------------------------------------------------------
 * DATE:	03 Jul 2015
 * AUTHOR:	Cloete A.H
 * PROJECT:	M-Eng, Inteligent geyser M2M system.	
 * ---------------------------------------------------------------------------------------------------------
 * DESCRIPTION: 
 * ---------------------------------------------------------------------------------------------------------
 * PURPOSE: 
 * ---------------------------------------------------------------------------------------------------------
 */

package acza.sun.ee.GeyserM2M;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.om2m.commons.resource.Application;
import org.eclipse.om2m.commons.resource.ContentInstance;
import org.eclipse.om2m.commons.resource.Notify;
import org.eclipse.om2m.commons.resource.StatusCode;
import org.eclipse.om2m.commons.utils.XmlMapper;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import acza.sun.ee.geyserM2M.GeyserApplication;
import acza.sun.ee.geyserM2M.SCLapi;


public class Setpointcontroller {

	private static final Logger logger = LogManager.getLogger(Setpointcontroller.class);
	private static SCLapi nscl;
	private static Map<Long, Integer> geyser_setpoint_map = new ConcurrentHashMap<Long, Integer>();//<geyser_ID, setpiont> 
	
	private static String APOC_URL;
	private static int APOC_PORT;
	private static String APOC;
	
	private static String app_ID = "Setpointcontroller";
	private static String setpoint_app_URI = "setpointapp";
	private static String setpoint_data_URI = "setpointdata";
	private static String setpoint_settings_URI = "setpointsetting";
	
	private static int DEFAULT_SETPOINT = 45;
	private static int DEADBAND = 1;
	
	
	public static void main(String[] args) {
		// ---------------------- Sanity checking of command line arguments -------------------------------------------
				if( args.length != 3)
				{
					System.out.println( "Usage: <NSCL IP address> <aPoc URL> <aPoc PORT>" ) ;
					return;
				}

				final String NSCL_IP_ADD = args[0];//"52.10.236.177";//"localhost";//
				if(!ipAddressValidator(NSCL_IP_ADD)){
					System.out.println( "IPv4 address invalid." ) ;
					return;
				}

				APOC_URL = args[1];
				try{
					APOC_PORT = Integer.parseInt( args[2] ); // Convert the argument to ensure that is it valid
				}catch ( Exception e ){
					System.out.println( "aPoc port invalid." ) ;
					return;
				}
				APOC = APOC_URL + ":" + APOC_PORT;
				//---------------------------------------------------------------------------------------------------------------

				logger.info("GeyserSetpointcontroller usage: <NSCL IP address> <aPoc URL> <aPoc PORT>");
				logger.info("GeyserSetpointcontroller started with parameters: " + args[0] + " " + args[1] + " " + args[2]);
				
				/* ***************************** START APOC SERVER ************************************************/
				Server server = new Server(APOC_PORT);

				ServletHandler handler = new ServletHandler();
				server.setHandler(handler);

				// IMPORTANT:
				// This is a raw Servlet, not a Servlet that has been configured
				// through a web.xml @WebServlet annotation, or anything similar.
				handler.addServletWithMapping(ApocServlet.class, "/*");

				try {
					server.start();
					logger.info("Apoc server started.");
				} catch (Exception e) {
					logger.fatal("Apoc server failed to start.", e);
					return;
				}
				/* ********************************************************************************************/
				
				
				nscl = new SCLapi("nscl", NSCL_IP_ADD, "8080", "admin:admin");
				nscl.registerApplication(app_ID);
				
				//Look for all existing GEYSER applications and subscribe to them.
				List<String> appList = nscl.retrieveApplicationList();
				for(String app : appList){
					if(app.startsWith("geyser")){
						long geyser_id = getGeyserIdFromString(app);
						geyser_setpoint_map.put(geyser_id, DEFAULT_SETPOINT);
						nscl.createContainer(app_ID, "SETPOINT_"+geyser_id);
						nscl.createContentInstance(app_ID, "SETPOINT_"+geyser_id, String.valueOf(DEFAULT_SETPOINT));
						nscl.subscribeToContent(app_ID, "SETPOINT_"+geyser_id, setpoint_settings_URI+"_"+geyser_id, APOC);
						nscl.subscribeToContent(geyser_id, "DATA", setpoint_data_URI, APOC);
					}
					
				}
				
				nscl.subscribeToApplications(setpoint_app_URI, APOC);

	}
	
	@SuppressWarnings("serial")
	public static class ApocServlet extends HttpServlet {
	}
	
	private static long getGeyserIdFromString(String appId){
		try{
			return new Long(appId.substring(appId.lastIndexOf("_")+1));
		} catch (Exception e){
			logger.error("Geyser ID failure. Using defualt ID '0000'"); 
			return (long)0000;
		}
	}
	
	private static Object getValueFromJSON(String key, String JSON) throws ParseException{

		JSONParser parser=new JSONParser();

		Object obj = parser.parse(JSON);
		JSONArray array = new JSONArray();
		array.add(obj);	
		JSONObject jobj = (JSONObject)array.get(0);

		return jobj.get(key);
	}
	
	private static boolean ipAddressValidator(final String ip_adr){
		
		if(ip_adr.equalsIgnoreCase("localhost"))
			return true;
		
		 Pattern adr_pattern = Pattern.compile("^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$", Pattern.DOTALL);
		 Matcher matcher = adr_pattern.matcher(ip_adr);
		 return matcher.matches();
	}

}


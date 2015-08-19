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
import java.util.List;
import java.util.Map;
import java.util.Properties;

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

import acza.sun.ee.geyserM2M.SCLapi;


public class Setpointcontroller {

	private static final Logger logger = LogManager.getLogger(Setpointcontroller.class);
	private static SCLapi nscl;
	private static Map<Long, Integer> geyser_setpoint_map = new ConcurrentHashMap<Long, Integer>();//<geyser_ID, setpiont> 
	
	private static String NSCL_BASE_URL;
	private static String AUTH;
	private static String APOC_URL;
	private static int APOC_PORT;
	private static String APOC;
	
	private static String app_ID = "Setpointcontroller";
	private static String setpoint_app_URI = "setpointapp"; //(1)
	private static String setpoint_data_URI = "setpointdata";
	private static String setpoint_settings_URI = "setpointsetting";
	
	private static int DEFAULT_SETPOINT;
	private static int DEADBAND;
	
	
	public static void main(String[] args) {
		// ---------------------- Reading and sanity checking configuration parameters -------------------------------------------

		Properties configFile = new Properties();
		try {
			configFile.load(Setpointcontroller.class.getClassLoader().getResourceAsStream("config.properties"));

			NSCL_BASE_URL = configFile.getProperty("NSCL_BASE_URL");
			AUTH = configFile.getProperty("AUTH");		
			APOC_URL = configFile.getProperty("APOC_URL");	

			try{
				APOC_PORT = Integer.parseInt(configFile.getProperty("APOC_PORT")); // Convert the argument to ensure that is it valid
			}catch ( Exception e ){
				System.out.println( "APOC_PORT parameter invalid." ) ;
				return;
			}
			APOC = APOC_URL + ":" + APOC_PORT;
			
			try{
				DEADBAND = Integer.parseInt( configFile.getProperty("DEADBAND") );
			}catch ( Exception e ){
				System.out.println( "DEADBAND parameter invalid." ) ;
				return;
			}

			try{
				DEFAULT_SETPOINT = Integer.parseInt( configFile.getProperty("DEFAULT_SETPOINT") );
			}catch ( Exception e ){
				System.out.println( "DEFAULT_SETPOINT parameter invalid." ) ;
				return;
			}

			logger.info("GeyserSetpointcontroller started with: " + configFile.toString());

		} catch (IOException e) {
			logger.fatal("Error in configuration file \"config.properties\"", e);
			return;
		}

		//---------------------------------------------------------------------------------------------------------------

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


		//nscl = new SCLapi("nscl", NSCL_IP_ADD, "8080", "admin:admin");
		if(AUTH.equalsIgnoreCase("NONE"))
			nscl = new SCLapi(NSCL_BASE_URL);	//OpenMTC
		else
			nscl = new SCLapi(NSCL_BASE_URL, AUTH); //OM2M

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
		
		@Override
		protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

			String requestURI = request.getRequestURI();
			
			InputStream in = request.getInputStream();
			InputStreamReader inr = new InputStreamReader(in);
			BufferedReader bin = new BufferedReader(inr);

			StringBuilder builder = new StringBuilder();
			String line;
			while((line = bin.readLine()) != null){
				builder.append(line);
			}
			
			
			XmlMapper xm = XmlMapper.getInstance();
			Notify notify = (Notify) xm.xmlToObject(builder.toString());
			System.out.println("Inbound notification: " + notify.getStatusCode() + " -- Request URI: " + requestURI);

			String target_resource = requestURI.substring(requestURI.lastIndexOf("/")+1);
			if(target_resource.equalsIgnoreCase("application")){ //(1)
				Application app = (Application) xm.xmlToObject(new String(notify.getRepresentation().getValue(), StandardCharsets.ISO_8859_1));
				if(app.getAppId().startsWith("geyser")){
					if(notify.getStatusCode().equals(StatusCode.STATUS_CREATED)){
						long geyser_id = getGeyserIdFromString(app.getAppId());
						geyser_setpoint_map.put(geyser_id, DEFAULT_SETPOINT);
						nscl.createContainer(app_ID, "SETPOINT_"+geyser_id);
						nscl.createContentInstance(app_ID, "SETPOINT_"+geyser_id, String.valueOf(DEFAULT_SETPOINT));
						nscl.subscribeToContent(app_ID, "SETPOINT_"+geyser_id, setpoint_settings_URI+"_"+geyser_id, APOC);
						nscl.subscribeToContent(geyser_id, "DATA", setpoint_data_URI, APOC);
					
						logger.info("New application registered, Geyser: " + geyser_id);
					}
					else if(notify.getStatusCode().equals(StatusCode.STATUS_DELETED)){
						logger.warn("Application deregistered : " + app.getAppId());
					}
					else{
						logger.warn("Unexpexted application notification status code.");
					}
					System.out.println();
				}
			}
			else if(target_resource.startsWith(setpoint_settings_URI)){
				long target_geyserclient_id = getGeyserIdFromString(target_resource);
				ContentInstance ci = (ContentInstance) xm.xmlToObject(new String(notify.getRepresentation().getValue(), StandardCharsets.ISO_8859_1));
				String setpoint_str = new String(ci.getContent().getValue(), StandardCharsets.ISO_8859_1);
				System.out.println("Inbound setpoint string for Geyser "+ target_geyserclient_id +": " + setpoint_str);
				
				try{
					int setpoint = Integer.parseInt(setpoint_str);
					geyser_setpoint_map.put(target_geyserclient_id, setpoint);
				}catch(Exception e){
					logger.warn("Invalid setpoint format.", e);
				}
				
			}
			else if(target_resource.startsWith(setpoint_data_URI)){
				long target_geyserclient_id = getGeyserIdFromString(target_resource);
				ContentInstance ci = (ContentInstance) xm.xmlToObject(new String(notify.getRepresentation().getValue(), StandardCharsets.ISO_8859_1));
				String jsonDatapoint = new String(ci.getContent().getValue(), StandardCharsets.ISO_8859_1);
				System.out.println("Inbound data point from Geyser "+ target_geyserclient_id +": " + jsonDatapoint);
				
				try{
					String rs = (String)getValueFromJSON("Rstate", jsonDatapoint);
					long t1 = (long)getValueFromJSON("T1", jsonDatapoint);
					int setpoint = geyser_setpoint_map.get(target_geyserclient_id);
					
					if(t1 <= 30)
						logger.warn("Geyser " + target_geyserclient_id + " internal at " + t1 + " degrees");
					
					if(t1 >= setpoint + DEADBAND){
						//If not already OFF, post "OFF"
						if(!rs.equalsIgnoreCase("OFF")){
							nscl.createContentInstance(target_geyserclient_id, "SETTINGS", "{\"Rstate\":\"OFF\"}");
							System.out.println("Switching geyser " + target_geyserclient_id + " OFF");
						}
					}
					else if(t1 <= setpoint - DEADBAND){
						//If not already ON,post "ON"
						if(!rs.equalsIgnoreCase("ON")){
							nscl.createContentInstance(target_geyserclient_id, "SETTINGS", "{\"Rstate\":\"ON\"}");
							System.out.println("Switching geyser " + target_geyserclient_id + " ON");
						}
					}
					
				}catch(ParseException pe){
					logger.error("Inbound datapoint from Geyser: " + target_geyserclient_id + " json parse exception");
				}
				
			}
			else{
				logger.warn("Unknown target resource apoc recieved: " + target_resource);
			}

		}//End of doPOST
	}//End of Apoc servlet
	
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
}

/*
 * (1)
 * Oops. I was inconsistent with my scheme for aPoC URI's. Compare "subscribeToApplications" with "subscribeToContent" in SCLapi.java
 * to see the problem. Not such a big deal for now. But it might be confusing for future updates. 
 * 
 * 
 * 
 * 
 */


/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.compiere.util;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.adempiere.base.Core;
import org.compiere.Adempiere;
import org.compiere.db.AdempiereDatabase;
import org.compiere.db.CConnection;
import org.compiere.model.MClient;
import org.idempiere.distributed.IClusterMember;
import org.idempiere.distributed.IClusterService;


/**
 *	idempiere Log Management.
 *
 *  @author Jorg Janke
 *  @version $Id: CLogMgt.java,v 1.4 2006/07/30 00:54:36 jjanke Exp $
 */
public class CLogMgt
{
	private static final CLogConsole CONSOLE_HANDLER = new CLogConsole();
	private static final CLogErrorBuffer ERROR_BUFFER_HANDLER = new CLogErrorBuffer();
	private static CLogFile fileHandler;
	
	private static final Map<String, Level> levelMap = new HashMap<String, Level>();
	
	private final static Runnable configurationListener = new Runnable() {
		@Override
		public void run() {
			reInit();
		}
	};
	
	private static synchronized void reInit() {
		CLogMgt.initialize(Ini.isClient());
		if (!levelMap.isEmpty()) {
			for(String key : levelMap.keySet()) {
				setLevel(key, levelMap.get(key));
			}
		}
		if (fileHandler != null) {
			fileHandler.reopen();
		}
	}
	
	/**
	 * 	Initialize Logging
	 * 	@param isClient client
	 */
	public static synchronized void initialize(boolean isClient)
	{
		LogManager mgr = LogManager.getLogManager();
		if (isClient)
		{			
			try
			{	//	Load Logging config from org.compiere.util.*properties
				String fileName = "logClient.properties";
				InputStream in = CLogMgt.class.getResourceAsStream(fileName);
				BufferedInputStream bin = new BufferedInputStream(in);
				mgr.readConfiguration(bin);
				in.close();
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}

		//	Handler List
		List<String>handlerNames = new ArrayList<String>();
		try
		{
			Logger rootLogger = getRootLogger();
			
		//	System.out.println(rootLogger.getName() + " (" + rootLogger + ")");
			Handler[] handlers = rootLogger.getHandlers();
			for (int i = 0; i < handlers.length; i ++)
			{
				handlerNames.add(handlers[i].getClass().getName());
			}
			/**
			Enumeration en = mgr.getLoggerNames();
			while (en.hasMoreElements())
			{
				Logger lll = Logger.getLogger(en.nextElement().toString());
				System.out.println(lll.getName() + " (" + lll + ")");
			//	System.out.println("- level=" + lll.getLevel());
			//	System.out.println("- parent=" + lll.getParent() + " - UseParentHandlers=" + lll.getUseParentHandlers());
			//	System.out.println("- filter=" + lll.getFilter());
				handlers = lll.getHandlers();
			//	System.out.println("- handlers=" + handlers.length);
				for (int i = 0; i < handlers.length; i ++)
				{
					System.out.println("  > " + handlers[i]);
					if (!s_handlers.contains(handlers[i]))
						s_handlers.add(handlers[i]);
				}
				//	System.out.println();
			}
			/** **/
		}
		catch (Exception e)
		{
			if (e instanceof ClassNotFoundException)	//	WebStart
				;
			/**
			Can't load log handler "org.compiere.util.CLogConsole"
			java.lang.ClassNotFoundException: org.compiere.util.CLogConsole
			java.lang.ClassNotFoundException: org.compiere.util.CLogConsole
				at java.net.URLClassLoader$1.run(Unknown Source)
				at java.security.AccessController.doPrivileged(Native Method)
				at java.net.URLClassLoader.findClass(Unknown Source)
				at java.lang.ClassLoader.loadClass(Unknown Source)
				at sun.misc.Launcher$AppClassLoader.loadClass(Unknown Source)
				at java.lang.ClassLoader.loadClass(Unknown Source)
				at java.util.logging.LogManager$7.run(Unknown Source)
				at java.security.AccessController.doPrivileged(Native Method)
				at java.util.logging.LogManager.initializeGlobalHandlers(Unknown Source)
				at java.util.logging.LogManager.access$900(Unknown Source)
				at java.util.logging.LogManager$RootLogger.getHandlers(Unknown Source)
				at org.compiere.util.CLogMgt.initialize(CLogMgt.java:67)
				at org.compiere.Adempiere.startup(Adempiere.java:389)
				at org.compiere.Adempiere.main(Adempiere.java:500)
				at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
				at sun.reflect.NativeMethodAccessorImpl.invoke(Unknown Source)
				at sun.reflect.DelegatingMethodAccessorImpl.invoke(Unknown Source)
				at java.lang.reflect.Method.invoke(Unknown Source)
				at com.sun.javaws.Launcher.executeApplication(Unknown Source)
				at com.sun.javaws.Launcher.executeMainClass(Unknown Source)
				at com.sun.javaws.Launcher.continueLaunch(Unknown Source)
				at com.sun.javaws.Launcher.handleApplicationDesc(Unknown Source)
				at com.sun.javaws.Launcher.handleLaunchFile(Unknown Source)
				at com.sun.javaws.Launcher.run(Unknown Source)
				at java.lang.Thread.run(Unknown Source)
			**/
			else
				System.err.println(e.toString());
		}
		//	Check Loggers
		if (!handlerNames.contains(CLogErrorBuffer.class.getName()))
			addHandler(ERROR_BUFFER_HANDLER);
		if (isClient && !handlerNames.contains(CLogConsole.class.getName()))
			addHandler(CONSOLE_HANDLER);
		if (!handlerNames.contains(CLogFile.class.getName()))
		{
			if (fileHandler == null)
				fileHandler = new CLogFile(null, true, isClient);
			
			addHandler(fileHandler);
		}

		setFormatter(CLogFormatter.get());
		setFilter(CLogFilter.get());
	
		mgr.removeConfigurationListener(configurationListener);
		mgr.addConfigurationListener(configurationListener);
	}	//	initialize


	/** Logger				*/
	private static Logger		log = Logger.getAnonymousLogger();
	/** LOG Levels			*/
	public static final Level[]	LEVELS = new Level[]
		{Level.OFF, Level.SEVERE, Level.WARNING, Level.INFO,
		Level.CONFIG, Level.FINE, Level.FINER, Level.FINEST, Level.ALL};

	/** New Line			*/
	private static final String NL = System.getProperty("line.separator");

	/**
	 * 	Get Handlers
	 *	@return handlers
	 */
	protected static Handler[] getHandlers()
	{
		Logger rootLogger = getRootLogger();
		Handler[] handlers = rootLogger.getHandlers();
		return handlers;
	}	//	getHandlers

	/**
	 * 	Add Handler (to root logger)
	 *	@param handler new Handler
	 */
	public static void addHandler(Handler handler)
	{
		if (handler == null)
			return;
		Logger rootLogger = getRootLogger();
		rootLogger.addHandler(handler);
		//
		if (log.isLoggable(Level.CONFIG))log.log(Level.CONFIG, "Handler=" + handler);
	}	//	addHandler


	/**
	 * 	Set Formatter for all handlers
	 *	@param formatter formatter
	 */
	protected static void setFormatter (java.util.logging.Formatter formatter)
	{
		Logger rootLogger = getRootLogger();
		Handler[] handlers = rootLogger.getHandlers();
		for (int i = 0; i < handlers.length; i++)
		{
			handlers[i].setFormatter(formatter);
		}
		if (log.isLoggable(Level.CONFIG))log.log(Level.CONFIG, "Formatter=" + formatter);
	}	//	setFormatter

	/**
	 * 	Set Filter for all handlers
	 *	@param filter filter
	 */
	protected static void setFilter (Filter filter)
	{
		Logger rootLogger = getRootLogger();
		Handler[] handlers = rootLogger.getHandlers();
		for (int i = 0; i < handlers.length; i++)
		{
			handlers[i].setFilter(filter);
		}
		if (log.isLoggable(Level.CONFIG))log.log(Level.CONFIG, "Filter=" + filter);
	}	//	setFilter

	/**
	 * 	Set Level for all Loggers
	 *	@param level log level
	 *	@param loggerNamePart optional partial class/logger name
	 */
	public static void setLoggerLevel (Level level, String loggerNamePart)
	{
		if (level == null)
			return;
		LogManager mgr = LogManager.getLogManager();
		Enumeration<?> en = mgr.getLoggerNames();
		while (en.hasMoreElements())
		{
			String name = en.nextElement().toString();
			if (loggerNamePart == null
				|| name.indexOf(loggerNamePart) != -1)
			{
				Logger lll = Logger.getLogger(name);
				lll.setLevel(level);
			}
		}
	}	//	setLoggerLevel

	/**
	 * 	Set Level for all handlers
	 *	@param level log level
	 */
	public static void setLevel (Level level)
	{
		setLevel(null, level);
	}
	
	/**
	 * 	Set Level for all handlers
	 *	@param level log level
	 */
	public static synchronized void setLevel (String loggerName, Level level)
	{
		if (level == null)
			return;
		Logger logger = loggerName == null || loggerName.trim().length() == 0 ? getRootLogger() : CLogger.getCLogger(loggerName, false);
		logger.setLevel(level);
		
		if (loggerName == null || loggerName.trim().length() == 0)
		{
			Handler[] handlers = logger.getHandlers();
			if (handlers == null || handlers.length == 0)
			{
				initialize(true);
			}
			else
			{
				for (Handler handler : handlers)
				{
					handler.setLevel(level);
				}
			}

			//	JDBC if ALL
			setJDBCDebug(level.intValue() == Level.ALL.intValue());

			// Set the log level for all the existing loggers
			LogManager mgr = LogManager.getLogManager();
			Iterator<String> ln = mgr.getLoggerNames().asIterator();
			while (ln.hasNext())
			{
				String cl = ln.next();
				CLogger.getCLogger(cl, false).setLevel(level);
			}
			getRootLogger().setLevel(level);
		}
		else
		{
			if (!logger.getUseParentHandlers()) 
			{
				logger.setUseParentHandlers(true);
			}
		}
		String key = loggerName == null ? "" : loggerName;
		if (!levelMap.containsKey(key))
			levelMap.put(key, level);
	}	//	setHandlerLevel

	/**
	 * 	Set Level
	 *	@param intLevel integer value of level
	 */
	public static void setLevel (int intLevel)
	{
		setLevel(String.valueOf(intLevel));
	}	//	setLevel

	/**
	 * 	Set Level
	 *	@param levelString string representation of level
	 */
	public static void setLevel (String levelString)
	{
		setLevel(null, levelString);
	}	//	setLevel
	
	public static void setLevel(String loggerName, String levelString)
	{
		if (levelString == null)
			return;
		//
		for (int i = 0; i < LEVELS.length; i++)
		{
		    if (LEVELS[i].getName().equals(levelString))
		    {
		    	setLevel(loggerName, LEVELS[i]);
		    	return;
		    }
		}
		if (log.isLoggable(Level.CONFIG))log.log(Level.CONFIG, "Ignored: " + levelString);
	}

	/**
	 * 	Set JDBC Debug
	 *	@param enable
	 */
	public static void setJDBCDebug(boolean enable)
	{
		if (enable)
			DriverManager.setLogWriter(new PrintWriter(System.err));
		else
			DriverManager.setLogWriter(null);
	}	//	setJDBCDebug

	/**
	 * 	Get logging Level of handlers
	 *	@return logging level
	 */
	public static Level getLevel()
	{
		Logger rootLogger = getRootLogger();
		return rootLogger.getLevel();
	}	//	getLevel

	/**
	 * 	Get logging Level of handlers
	 *	@return logging level
	 */
	public static int getLevelAsInt()
	{
		Logger rootLogger = getRootLogger();
		return rootLogger.getLevel().intValue();
	}	//	getLevel

	/**
	 * 	Is Logging Level logged
	 *	@param level level
	 *	@return true if it is logged
	 */
	public static boolean isLevel (Level level)
	{
		if (level == null)
			return false;
		return level.intValue() >= getLevelAsInt();
	}	//	isLevel

	/**
	 * 	Is Logging Level FINEST logged
	 *	@return true if it is logged
	 */
	public static boolean isLevelAll ()
	{
		return Level.ALL.intValue() == getLevelAsInt();
	}	//	isLevelFinest

	/**
	 * 	Is Logging Level FINEST logged
	 *	@return true if it is logged
	 */
	public static boolean isLevelFinest ()
	{
		return Level.FINEST.intValue() >= getLevelAsInt();
	}	//	isLevelFinest

	/**
	 * 	Is Logging Level FINER logged
	 *	@return true if it is logged
	 */
	public static boolean isLevelFiner ()
	{
		return Level.FINER.intValue() >= getLevelAsInt();
	}	//	isLevelFiner

	/**
	 * 	Is Logging Level FINE logged
	 *	@return true if it is logged
	 */
	public static boolean isLevelFine ()
	{
		return Level.FINE.intValue() >= getLevelAsInt();
	}	//	isLevelFine

	/**
	 * 	Is Logging Level INFO logged
	 *	@return true if it is logged
	 */
	public static boolean isLevelInfo ()
	{
		return Level.INFO.intValue() >= getLevelAsInt();
	}	//	isLevelFine

	/**
	 * 	Enable/Disable logging (of handlers)
	 *	@param enableLogging true if logging enabled
	 */
	public static void enable (boolean enableLogging)
	{
		Logger rootLogger = getRootLogger();

		if (enableLogging)
			setLevel(rootLogger.getLevel());
		else
		{
			setLevel(Level.OFF);
		}
	}	//	enable



	/**
	 * 	Shutdown Logging system
	 */
	public static void shutdown ()
	{
		LogManager mgr = LogManager.getLogManager();
		mgr.reset();
	}	//	shutdown


	/**
	 *  Print Properties
	 *
	 *  @param p Properties to print
	 *  @param description Description of properties
	 *  @param logIt if true write to Log (Level.Config), else to System.out
	 */
	public static void printProperties (Properties p, String description, boolean logIt)
	{
		if (p == null)
			return;
		if (logIt) {
			if (log.isLoggable(Level.INFO)) log.info(description + " - Size=" + p.size()
				+ ", Hash=" + p.hashCode() + "\n" + getLocalHost());
		} else {
			System.out.println("Log.printProperties = " + description + ", Size=" + p.size());
		}

		Object[] pp = p.keySet().toArray();
		Arrays.sort(pp);
		for (int i = 0; i < pp.length; i++)
		{
			String key = pp[i].toString();
			String value = p.getProperty(key);
			if (logIt) {
				if (log.isLoggable(Level.CONFIG)) log.config(key + "=" + value);
			} else {
				System.out.println("  " + key + " = " + value);
			}
		}
	}   //  printProperties


	/**
	 *  Get Adempiere System Info
	 *  @param sb buffer to append or null
	 *  @return Info as multiple Line String
	 */
	public static StringBuffer getInfo (StringBuffer sb)
	{
		if (sb == null)
			sb = new StringBuffer();
		final String eq = " = ";
		sb.append(getMsg("Host")).append(eq)        .append(getServerInfo()).append(NL);
		sb.append(getMsg("Database")).append(eq)    .append(getDatabaseInfo()).append(NL);
		sb.append(getMsg("Schema")).append(eq)      .append(CConnection.get().getDbUid()).append(NL);
		//
		sb.append(getMsg("AD_User_ID")).append(eq)  .append(Env.getContext(Env.getCtx(), Env.AD_USER_NAME)).append(NL);
		sb.append(getMsg("AD_Role_ID")).append(eq)  .append(Env.getContext(Env.getCtx(), Env.AD_ROLE_NAME)).append(NL);
		//
		sb.append(getMsg("AD_Client_ID")).append(eq).append(Env.getContext(Env.getCtx(), Env.AD_CLIENT_NAME)).append(NL);
		sb.append(getMsg("AD_Org_ID")).append(eq)   .append(Env.getContext(Env.getCtx(), Env.AD_ORG_NAME)).append(NL);
		//
		sb.append(getMsg("Date")).append(eq)        .append(Env.getContext(Env.getCtx(), Env.DATE)).append(NL);
		sb.append(getMsg("Printer")).append(eq)     .append(Env.getContext(Env.getCtx(), "#Printer")).append(NL);
		// Show Implementation Vendor / Version - teo_sarca, [ 1622855 ]
		sb.append(getMsg("ImplementationVendor")).append(eq).append(org.compiere.Adempiere.getImplementationVendor()).append(NL);
		sb.append(getMsg("ImplementationVersion")).append(eq).append(org.compiere.Adempiere.getImplementationVersion()).append(NL);
		//
		sb.append("iDempiereHome = ").append(Adempiere.getAdempiereHome()).append(NL);
		sb.append("iDempiereProperties = ").append(Ini.getPropertyFileName()).append(NL);
		sb.append(Env.getLanguage(Env.getCtx())).append(NL);
		MClient client = MClient.get(Env.getCtx());
		sb.append(client).append(NL);
		sb.append(getMsg("IsMultiLingualDocument"))
			.append(eq).append(client.isMultiLingualDocument()).append(NL);
		sb.append("BaseLanguage = ").append(Env.isBaseLanguage(Env.getCtx(), "AD_Window"))
			.append("/").append(Env.isBaseLanguage(Env.getCtx(), "C_UOM")).append(NL);
		sb.append(Adempiere.getJavaInfo()).append(NL);
		sb.append("java.io.tmpdir="+System.getProperty("java.io.tmpdir")).append(NL);
		sb.append(Adempiere.getOSInfo()).append(NL);

		//report memory info
		Runtime runtime = Runtime.getRuntime();
		//max heap size
		sb.append("Max Heap = "+formatMemoryInfo(runtime.maxMemory())).append(NL);
		//allocated heap size
		sb.append("Allocated Heap = "+formatMemoryInfo(runtime.totalMemory())).append(NL);
		//free heap size
		sb.append("Free Heap = "+formatMemoryInfo(runtime.freeMemory())).append(NL);
		//
		//thread info
		sb.append("Active Threads = " + Thread.activeCount());
		//
		//cluster info
		if (Env.getAD_Client_ID(Env.getCtx()) == 0) {
			IClusterService service = Core.getClusterService();
			if (service != null) {
				IClusterMember local = service.getLocalMember();
				Collection<IClusterMember> members = service.getMembers();				
				if (!members.isEmpty())
					sb.append(NL).append("Cluster Nodes:").append(NL);
				for(IClusterMember member : members) {					
					sb.append(member.toString());
					if (local != null && member.getId().equals(local.getId())) {
						sb.append(" *");
					}
					sb.append(NL);					
				}
			}
		}
		
		return sb;
	}   //  getInfo

	private static String formatMemoryInfo(long amount)
	{
		String unit = "";
		long size = amount / 1024 ;
		if (size > 1024)
		{
			size = size / 1024;
			unit = "M";
		}
		else
		{
			unit = "K";
		}
		return size + unit;
	}

	/**
	 *  Create System Info
	 *  @param sb Optional string buffer
	 *  @param ctx Environment
	 *  @return System Info
	 */
	public static StringBuffer getInfoDetail (StringBuffer sb, Properties ctx)
	{
		if (sb == null)
			sb = new StringBuffer();
		if (ctx == null)
			ctx = Env.getCtx();
		//  Envoronment
		CConnection cc = CConnection.get();
		sb.append(NL).append(NL)
			.append("=== Environment === ").append(NL)
			.append(Adempiere.getCheckSum()).append(NL)
			.append(Adempiere.getSummaryAscii()).append(NL)
			.append(getLocalHost()).append(NL)
			.append(cc.getName() + " " + cc.getDbUid() + "@" + cc.getConnectionURL()).append(NL)
			.append(cc.getInfo()).append(NL);
		
		//connection pool
		sb.append(NL)
			.append("=== DB Connection Pool === ").append(NL)
			.append(cc.getDatabase().getStatus().replace(" , ", NL)).append(NL);
		
		//  Context
		String[] context = Env.getEntireContext(ctx);
		sb.append(NL)
			.append("=== Context (").append(context.length).append(") ===").append(NL);
		Arrays.sort(context);
		for (int i = 0; i < context.length; i++)
			sb.append(context[i]).append(NL);
		//  System
		sb.append(NL)
			.append("=== System ===").append(NL);
		Object[] pp = System.getProperties().keySet().toArray();
		Arrays.sort(pp);
		for (int i = 0; i < pp.length; i++)
		{
			String key = pp[i].toString();
			String value = System.getProperty(key);
			sb.append(key).append("=").append(value).append(NL);
		}
		
		return sb;
	}   //  getInfoDetail


	/**
	 *  Get translated Message, if DB connection exists
	 *  @param msg AD_Message
	 *  @return translated msg if connected
	 */
	private static String getMsg (String msg)
	{
		if (DB.isConnected())
			return Msg.translate(Env.getCtx(), msg);
		return msg;
	}   //  getMsg


	/**
	 *  Get Server Info.
	 *  @return host : port (NotActive) via CMhost : port
	 */
	private static String getServerInfo()
	{
		StringBuilder sb = new StringBuilder();
		CConnection cc = CConnection.get();
		//  Host
		sb.append(cc.getAppsHost());
		
		//
		return sb.toString();
	}   //  getServerInfo

	/**
	 *  Get Database Info
	 *  @return host : port : sid
	 */
	private static String getDatabaseInfo()
	{
		StringBuilder sb = new StringBuilder();
		sb.append(CConnection.get().getDbHost()).append(":")
			.append(CConnection.get().getDbPort()).append("/")
			.append(CConnection.get().getDbName());
		
		AdempiereDatabase db = DB.getDatabase();
		sb.append(" (").append(db.getName());
		if (!DB.isOracle()) 
		{
			sb.append(", ").append(db.isNativeMode() ? "Native Dialect" : "Oracle Dialect");
		}
		sb.append(")");
		//  Connection Manager
		if (CConnection.get().isViaFirewall())
			sb.append(getMsg("via")).append(" ")
				.append(CConnection.get().getFwHost()).append(" : ")
				.append(CConnection.get().getFwPort());

		return sb.toString();
	}   //  getDatabaseInfo

	/**
	 *  Get Localhost
	 *  @return local host
	 */
	private static String getLocalHost()
	{
		try
		{
			InetAddress id = InetAddress.getLocalHost();
			return id.toString();
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "getLocalHost", e);
		}
		return "-no local host info -";
	}   //  getLocalHost

	private static Logger getRootLogger()
	{
		Logger rootLogger = Logger.getLogger("");
		if (rootLogger.getUseParentHandlers())
		{
			rootLogger.setUseParentHandlers(false);
		}
		//set default level
		if (rootLogger.getLevel() == null)
		{
			rootLogger.setLevel(Level.WARNING);
		}
		
		return rootLogger;
	}
	
	/**************************************************************************
	 * 	CLogMgt
	 */
	public CLogMgt ()
	{
		testLog();
	}

	/**
	 * 	Test Log
	 */
	private void testLog()
	{
		final CLogger log1 = CLogger.getCLogger("test");
		//
		log1.log(Level.SEVERE, "severe");
		log1.warning("warning");
		log1.info("Info");
		log1.config("config");
		log1.fine("fine");
		log1.finer("finer");
		log1.entering("myClass", "myMethod", "parameter");
		log1.exiting("myClass", "myMethod", "result");
		log1.finest("finest");

		new Thread()
		{
			public void run()
			{
				log1.info("thread info");
			}
		}.start();

		try
		{
			Integer.parseInt("ABC");
		}
		catch (Exception e)
		{
			log1.log(Level.SEVERE, "error message", e);
		}
		if (log1.isLoggable(Level.INFO)){
			log1.log(Level.INFO, "info message 1", "1Param");
			log1.log(Level.INFO, "info message n", new Object[]{"1Param","2Param"});
		}
	}	//	testLog

	/**
	 * 	Test
	 *	@param args ignored
	 */
	public static void main (String[] args)
	{
		initialize(true);
		new CLogMgt();
	}	//	CLogMgt

}	//	CLogMgt

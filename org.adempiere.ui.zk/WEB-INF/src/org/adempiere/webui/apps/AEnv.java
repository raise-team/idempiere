/******************************************************************************
 * Product: Posterita Ajax UI 												  *
 * Copyright (C) 2007 Posterita Ltd.  All Rights Reserved.                    *
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
 * Posterita Ltd., 3, Draper Avenue, Quatre Bornes, Mauritius                 *
 * or via info@posterita.org or http://www.posterita.org/                     *
 *****************************************************************************/

package org.adempiere.webui.apps;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;

import javax.servlet.ServletRequest;

import org.adempiere.webui.ClientInfo;
import org.adempiere.webui.ISupportMask;
import org.adempiere.webui.LayoutUtils;
import org.adempiere.webui.adwindow.ADWindow;
import org.adempiere.webui.component.Mask;
import org.adempiere.webui.component.Window;
import org.adempiere.webui.desktop.IDesktop;
import org.adempiere.webui.editor.WTableDirEditor;
import org.adempiere.webui.event.DialogEvents;
import org.adempiere.webui.event.DrillEvent.DrillData;
import org.adempiere.webui.info.InfoWindow;
import org.adempiere.webui.session.SessionManager;
import org.adempiere.webui.theme.ThemeManager;
import org.adempiere.webui.util.IServerPushCallback;
import org.adempiere.webui.util.ServerPushTemplate;
import org.adempiere.webui.window.Dialog;
import org.compiere.acct.Doc;
import org.compiere.model.GridWindowVO;
import org.compiere.model.I_AD_Window;
import org.compiere.model.Lookup;
import org.compiere.model.MClient;
import org.compiere.model.MLanguage;
import org.compiere.model.MLookup;
import org.compiere.model.MLookupFactory;
import org.compiere.model.MQuery;
import org.compiere.model.MReference;
import org.compiere.model.MRole;
import org.compiere.model.MSession;
import org.compiere.model.MSysConfig;
import org.compiere.model.MTable;
import org.compiere.model.MZoomCondition;
import org.compiere.model.PO;
import org.compiere.util.CCache;
import org.compiere.util.CLogger;
import org.compiere.util.CacheMgt;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Ini;
import org.compiere.util.Language;
import org.compiere.util.Util;
import org.zkoss.web.servlet.Servlets;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Desktop;
import org.zkoss.zk.ui.Execution;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zul.Button;
import org.zkoss.zul.impl.InputElement;

import com.lowagie.text.DocumentException;

/**
 *  Static application environment and utilities methods.
 *
 *  @author 	Jorg Janke
 *  @version 	$Id: AEnv.java,v 1.2 2006/07/30 00:51:27 jjanke Exp $
 *
 *  Colin Rooney (croo) and kstan_79 RFE#1670185
 */
public final class AEnv
{
	/** Environment context attribute for Locale */
	public static final String LOCALE = Env.LOCALE;
	
	/**
	 *  Show window in the center of screen.
	 * 	@param window Window to position
	 */
	public static void showCenterScreen(Window window)
	{
		if (SessionManager.getAppDesktop() != null)
			SessionManager.getAppDesktop().showWindow(window, "center");
		else 
		{
			window.setPosition("center");
			window.setPage(getDesktop().getFirstPage());
			Object objMode = window.getAttribute(Window.MODE_KEY);
			final String mode = objMode != null ? objMode.toString() : Window.MODE_HIGHLIGHTED;
			if (Window.MODE_MODAL.equals(mode))
				window.doModal();
			else
				window.doHighlighted();
		}
	}   //  showCenterScreen

	/**
	 *  Set window position ({@link org.zkoss.zul.Window#setPosition(String)}) and show it.
	 * 	@param window Window to position
	 * 	@param position
	 */
	public static void showScreen(Window window, String position)
	{
		SessionManager.getAppDesktop().showWindow(window, position);
	}   //  showScreen

	/**
	 *	Position window in center of the parent window.
	 * 	@param parent Parent Window
	 * 	@param window Window to position
	 */
	public static void showCenterWindow(Window parent, Window window)
	{
		parent.appendChild(window);
		showScreen(window, "parent,center");
	}   //  showCenterWindow

	/**
	 *  Get Mnemonic character from text.
	 *  @param text text with '&amp;'
	 *  @return Mnemonic character or 0
	 */
	public static char getMnemonic (String text)
	{

		int pos = text.indexOf('&');
		if (pos != -1)					//	We have a nemonic
			return text.charAt(pos+1);
		return 0;

	}   //  getMnemonic


	/*************************************************************************
	 * 	Zoom to AD Window by AD_Table_ID and Record_ID.
	 *	@param AD_Table_ID
	 *	@param Record_ID
	 */
	public static void zoom (int AD_Table_ID, int Record_ID)
	{
		int AD_Window_ID = Env.getZoomWindowID(AD_Table_ID, Record_ID);
		//  Nothing to Zoom to
		if (AD_Window_ID == 0)
			return;
		MTable table = MTable.get(Env.getCtx(), AD_Table_ID);
		MQuery query = MQuery.getEqualQuery(table.getKeyColumns()[0], Record_ID);
		query.setZoomTableName(table.getTableName());
		query.setZoomColumnName(table.getKeyColumns()[0]);
		query.setZoomValue(Record_ID);
		zoom(AD_Window_ID, query);
	}	//	zoom

	/*************************************************************************
	 * 	Zoom to AD Window by AD_Table_ID and Record_ID.
	 *	@param AD_Table_ID
	 *	@param Record_ID
	 *	@param query initial query for destination AD Window
	 *  @param windowNo
	 */
	public static void zoom (int AD_Table_ID, int Record_ID, MQuery query, int windowNo)
	{
		int AD_Window_ID = Env.getZoomWindowID(AD_Table_ID, Record_ID, windowNo);
		//  Nothing to Zoom to
		if (AD_Window_ID == 0)
			return;
		zoom(AD_Window_ID, query);
	}	//	zoom

	/**
	 * Call {@link #zoom(int, int, MQuery, int)}
	 * @param AD_Table_ID
	 * @param Record_ID
	 * @param query
	 */
	public static void zoom (int AD_Table_ID, int Record_ID, MQuery query) {
		zoom (AD_Table_ID, Record_ID, query, 0);
	}

	/**
	 *	Exit System.
	 *  @param status System exit status (usually 0 for no error)
	 */
	@Deprecated(forRemoval = true, since = "11")
	public static void exit (int status)
	{
		Env.exitEnv(status);
	}	//	exit

	/**
	 * Logout AD_Session and clear {@link #windowCache}.
	 */
	public static void logout()
	{
		String sessionID = Env.getContext(Env.getCtx(), Env.AD_SESSION_ID);
		synchronized (windowCache)
		{
			CCache<Integer,GridWindowVO> cache = windowCache.get(sessionID);
			if (cache != null)
			{
				cache.clear();
				CacheMgt.get().unregister(cache);
			}
		}
		windowCache.remove(sessionID);
		//	End Session
		int ad_Session_ID = Env.getContextAsInt(Env.getCtx(), Env.AD_SESSION_ID);
		MSession session = ad_Session_ID > 0 ? new MSession(Env.getCtx(), ad_Session_ID, null) : null;	//	finish
		if (session != null)
			session.logout();
		
		Env.setContext(Env.getCtx(), Env.AD_SESSION_ID, (String)null);
	}

	/**
	 * 	Open Workflow Process Window for AD_Table_ID and Record_ID
	 *	@param AD_Table_ID
	 *	@param Record_ID
	 */
	public static void startWorkflowProcess (int AD_Table_ID, int Record_ID)
	{
		if (s_workflow_Window_ID <= 0)
		{
			int AD_Window_ID = DB.getSQLValue(null, "SELECT AD_Window_ID FROM AD_Window WHERE Name = 'Workflow Process'");
			s_workflow_Window_ID = AD_Window_ID;
		}

		if (s_workflow_Window_ID <= 0)
			return;

		MQuery query = new MQuery();
		query.addRestriction("AD_Table_ID", MQuery.EQUAL, AD_Table_ID);
		query.addRestriction("Record_ID", MQuery.EQUAL, Record_ID);
		AEnv.zoom(s_workflow_Window_ID, query);
	}	//	startWorkflowProcess

	/** Cache Workflow Window ID */
	private static int		s_workflow_Window_ID = 0;
	/**	Logger			*/
	private static final CLogger log = CLogger.getCLogger(AEnv.class);

	/**	Register AD Window Cache */
	private static Map<String, CCache<Integer,GridWindowVO>> windowCache = new HashMap<String, CCache<Integer,GridWindowVO>>();

	/**
	 *  Get VO for AD_Window
	 *
	 *  @param WindowNo  Window No
	 *  @param AD_Window_ID window
	 *  @param AD_Menu_ID menu
	 *  @return {@link GridWindowVO} instance for AD_Window_ID
	 */
	public static GridWindowVO getMWindowVO (int WindowNo, int AD_Window_ID, int AD_Menu_ID)
	{
		if (log.isLoggable(Level.CONFIG)) log.config("Window=" + WindowNo + ", AD_Window_ID=" + AD_Window_ID);
		GridWindowVO mWindowVO = null;
		String sessionID = Env.getContext(Env.getCtx(), Env.AD_SESSION_ID);
		if (AD_Window_ID != 0 && Ini.isCacheWindow())	//	try cache
		{
			synchronized (windowCache)
			{
				CCache<Integer,GridWindowVO> cache = windowCache.get(sessionID);
				if (cache != null)
				{
					mWindowVO = cache.get(AD_Window_ID);
					if (mWindowVO != null)
					{
						mWindowVO = mWindowVO.clone(WindowNo);
						if (log.isLoggable(Level.INFO))
							log.info("Cached=" + mWindowVO);
					}
				}
			}
		}

		//  Create Window Model on Client
		if (mWindowVO == null)
		{
			if (log.isLoggable(Level.CONFIG))
				log.config("create local");
			mWindowVO = GridWindowVO.create (Env.getCtx(), WindowNo, AD_Window_ID, AD_Menu_ID);
			if (mWindowVO != null && Ini.isCacheWindow())
			{
				synchronized (windowCache)
				{
					CCache<Integer,GridWindowVO> cache = windowCache.get(sessionID);
					if (cache == null)
					{
						cache = new CCache<Integer, GridWindowVO>(I_AD_Window.Table_Name, I_AD_Window.Table_Name+"|GridWindowVO|Session|"+sessionID, 10);
						windowCache.put(sessionID, cache);
					}
					cache.put(AD_Window_ID, mWindowVO);
				}
			}
		}	//	from Client
		if (mWindowVO == null)
			return null;

		//  Check context (Just in case, usually both is ServerContextPropertiesWrapper)
		if (!mWindowVO.ctx.equals(Env.getCtx()))
		{
			//  Add Window properties to context
			Enumeration<Object> keyEnum = mWindowVO.ctx.keys();
			while (keyEnum.hasMoreElements())
			{
				String key = (String)keyEnum.nextElement();
				if (key.startsWith(WindowNo+"|"))
				{
					String value = mWindowVO.ctx.getProperty (key);
					Env.setContext(Env.getCtx(), key, value);
				}
			}
			//  Sync Context
			mWindowVO.setCtx(Env.getCtx());
		}
		return mWindowVO;

	}   //  getWindow

	/**
	 *  Post Immediate.
	 *  Call {@link Doc#manualPosting(int, int, int, int, boolean)}.
	 *  @param  WindowNo 		window
	 *  @param  AD_Table_ID     Table ID of Document
	 *  @param  AD_Client_ID    Client ID of Document
	 *  @param  Record_ID       Record ID of Document
	 *  @param  force           force posting. if false, only post if (Processing='N' OR Processing IS NULL)
	 *  @return null if success, otherwise error
	 */
	public static String postImmediate (int WindowNo, int AD_Client_ID,
		int AD_Table_ID, int Record_ID, boolean force)
	{

		log.info("Window=" + WindowNo
			+ ", AD_Table_ID=" + AD_Table_ID + "/" + Record_ID
			+ ", Force=" + force);

		return Doc.manualPosting(WindowNo, AD_Client_ID, AD_Table_ID, Record_ID, force);
	}   //  postImmediate

	/**
	 *  Cache Reset
	 *  @param  tableName	table name
	 *  @param  Record_ID	record id
	 */
	public static void cacheReset (String tableName, int Record_ID)
	{

		if (log.isLoggable(Level.CONFIG)) log.config("TableName=" + tableName + ", Record_ID=" + Record_ID);

		CacheMgt.get().reset(tableName, Record_ID);
	}   //  cacheReset

	/**
	 * Refresh lookup
	 * @param lookup
	 * @param value
	 * @param mandatory
	 * @param shortList
	 */
    public static void actionRefresh(Lookup lookup, Object value, boolean mandatory, boolean shortList) // IDEMPIERE 90
    {
        if (lookup == null)
            return;

        lookup.refresh();
        if (lookup.isValidated())
            lookup.fillComboBox(mandatory, false, false, false, shortList); // IDEMPIERE 90
        else
            lookup.fillComboBox(mandatory, true, false, false, shortList); // IDEMPIERE 90
    }
    /**
     * zoom to AD Window
     * @param lookup lookup for zoom destination table
     * @param value record key
     */
    public static void actionZoom(Lookup lookup, Object value)
    {
        if (lookup == null)
            return;
		// still null means the field is empty or not selected item
		if (value == null)
			value = -1;
        //
        MQuery zoomQuery = new MQuery();   //  ColumnName might be changed in MTab.validateQuery
		String column = lookup.getColumnName();
		//	Check if it is a List Reference
		if (lookup instanceof MLookup)
		{
			int AD_Reference_ID = ((MLookup)lookup).getAD_Reference_Value_ID();
			if (AD_Reference_ID > 0)
			{
				MReference reference = MReference.get(AD_Reference_ID);
				if (reference.getValidationType().equals(MReference.VALIDATIONTYPE_ListValidation))
				{
					column = "AD_Ref_List_ID";
					value = DB.getSQLValue(null, "SELECT AD_Ref_List_ID FROM AD_Ref_List WHERE AD_Reference_ID=? AND Value=?", AD_Reference_ID, value);
				}
			}
		}
		//strip off table name, fully qualify name doesn't work when zoom into detail tab
		if (column.indexOf(".") > 0)
		{
			int p = column.indexOf(".");
			String tableName = column.substring(0, p);
			column = column.substring(column.indexOf(".")+1);
			zoomQuery.setZoomTableName(tableName);
			zoomQuery.setZoomColumnName(column);
		}
		else
		{
			zoomQuery.setZoomColumnName(column);
			//remove _ID to get table name
			zoomQuery.setZoomTableName(column.substring(0, column.length() - 3));
		}
		zoomQuery.setZoomValue(value);
		zoomQuery.addRestriction(column, MQuery.EQUAL, value);
		zoomQuery.setRecordCount(1);    //  guess
        if (value instanceof Integer && ((Integer) value).intValue() >= 0 && zoomQuery != null && zoomQuery.getZoomTableName() != null) {
        	int tableId = MTable.getTable_ID(zoomQuery.getZoomTableName());
        	zoom(tableId, ((Integer) value).intValue(), zoomQuery, lookup.getWindowNo());
        } else {
        	int windowId = lookup.getZoom(zoomQuery);
        	zoom(windowId, zoomQuery, lookup.getWindowNo());
        }
    }

    /**
	 *  Opens the Drill Assistant
	 * 	@param data query
	 *  @param windowNo
	 */
    public static void actionDrill(DrillData data, int windowNo) {
	actionDrill(data, windowNo, 0);
    }

    /**
	 *  Opens the Drill Assistant
	 * 	@param data query
	 *  @param windowNo
	 *  @param processID Source Report
	 */
    public static void actionDrill(DrillData data, int windowNo, int processID) {
	int AD_Table_ID = MTable.getTable_ID(data.getQuery().getTableName());
		if (AD_Table_ID > 0) {
			if (!MRole.getDefault().isCanReport(AD_Table_ID))
			{
				Dialog.error(windowNo, "AccessCannotReport", data.getQuery().getTableName());
				return;
			}
			WDrillReport drillReport = new WDrillReport(data, windowNo, processID);

			Object window = SessionManager.getAppDesktop().findWindow(windowNo);
			if (window != null && window instanceof Component && window instanceof ISupportMask){
				final ISupportMask parent = LayoutUtils.showWindowWithMask(drillReport, (Component)window, LayoutUtils.OVERLAP_PARENT);
				drillReport.addEventListener(DialogEvents.ON_WINDOW_CLOSE, new EventListener<Event>() {
					@Override
					public void onEvent(Event event) throws Exception {
						parent.hideMask();
					}
				});
			}else if (window != null && window instanceof Component){
				final Mask mask = LayoutUtils.showWindowWithMask(drillReport, (Component)window, null);
				drillReport.addEventListener(DialogEvents.ON_WINDOW_CLOSE, new EventListener<Event>() {
					@Override
					public void onEvent(Event event) throws Exception {
						mask.hideMask();
					}
				});
			}else{
				drillReport.setAttribute(Window.MODE_KEY, Window.MODE_HIGHLIGHTED);
				AEnv.showWindow(drillReport);
			}
		}
		else
			log.warning("No Table found for " + data.getQuery().getWhereClause(true));
    }
    
    /**
     * open zoom window with query
     * @param AD_Window_ID
     * @param query
     */
    public static void showZoomWindow(int AD_Window_ID, MQuery query)
    {
    	SessionManager.getAppDesktop().showZoomWindow(AD_Window_ID, query);
    }
    
	/**
	 * Zoom to AD window with the provided window id and filters according to the
	 * query
	 * @param AD_Window_ID Window on which to zoom
	 * @param query Filter to be applied on the records.
	 */
	public static void zoom(int AD_Window_ID, MQuery query, int windowNo)
	{
		int zoomId = MZoomCondition.findZoomWindowByWindowId(AD_Window_ID, query, windowNo);
        showZoomWindow(zoomId > 0 ? zoomId : AD_Window_ID, query);
	}
	
	/**
	 * Call {@link #zoom(int, MQuery, int)}
	 * @param AD_Window_ID
	 * @param query
	 */
	public static void zoom(int AD_Window_ID, MQuery query) {
		zoom(AD_Window_ID, query, 0);
	}

	/**
	 * Show window in desktop.
	 * Call {@link IDesktop#showWindow(Window)}.
	 * @param win
	 */
	public static void showWindow(Window win)
	{
		SessionManager.getAppDesktop().showWindow(win);
	}

	/**
	 * 	Zoom to AD Window with details from query
	 *	@param query query
	 */
	public static void zoom (MQuery query)
	{
		if (query == null || query.getTableName() == null || query.getTableName().length() == 0)
			return;
		
		int AD_Window_ID = query.getZoomWindowID();

		if (AD_Window_ID <= 0)
			AD_Window_ID = Env.getZoomWindowID(query);

		//  Nothing to Zoom to
		if (AD_Window_ID == 0)
			return;

		showZoomWindow(AD_Window_ID, query);
	}

	/**
	 *  Get ImageIcon.
	 *
	 *  @param fileNameInImageDir full file name in imgaes folder (e.g. Bean16.png)
	 *  @return image {@link URI}
	 */
    public static URI getImage(String fileNameInImageDir)
    {
        URI uri = null;
        try
        {
            uri = new URI(ThemeManager.getThemeResource("images/" + fileNameInImageDir));
        }
        catch (URISyntaxException exception)
        {
            log.log(Level.SEVERE, "Not found: " +  fileNameInImageDir);
            return null;
        }
        return uri;
    }   //  getImageIcon

    /**
     * @return true if client browser is firefox 2+
     */
    public static boolean isFirefox2() {
    	Execution execution = Executions.getCurrent();
    	if (execution == null)
    		return false;

    	Object n = execution.getNativeRequest();
    	if (n instanceof ServletRequest) {
    		String userAgent = Servlets.getUserAgent((ServletRequest) n);
    		return userAgent.indexOf("Firefox/2") >= 0;
    	} else {
    		return false;
    	}
    }

    /**
     * @return boolean
     * @deprecated See IDEMPIERE-1022
     */
    public static boolean isBrowserSupported() {
    	Execution execution = Executions.getCurrent();
    	if (execution == null)
    		return false;

    	Object n = execution.getNativeRequest();
    	if (n instanceof ServletRequest) {
    		Double version = Servlets.getBrowser((ServletRequest) n, "ff");
    		if (version != null) {
    			return true;
    		}
    		
    		version = Servlets.getBrowser((ServletRequest) n, "chrome");
    		if (version != null) {
    			return true;
    		}
    		
    		version = Servlets.getBrowser((ServletRequest) n, "webkit");
    		if (version != null) {
    			return true;
    		}
    		
    		version = Servlets.getBrowser((ServletRequest) n, "ie");
    		if (version != null && version.intValue() >= 8)
    			return true;
    	}
    	return false;
    }

    /**
     * @return true if user agent is internet explorer
     */
    public static boolean isInternetExplorer()
    {
    	Execution execution = Executions.getCurrent();
    	if (execution == null)
    		return false;

    	Object n = execution.getNativeRequest();
    	if (n instanceof ServletRequest) {
    		String browser = Servlets.getBrowser((ServletRequest) n);
    		if (browser != null && browser.equals("ie"))
    			return true;
    		else
    			return false;
    	}
    	return false;
    }

    /**
     * @param parent
     * @param child
     * @return true if parent == child or parent is ancestor of child.
     */
    public static boolean contains(Component parent, Component child) {
    	if (child == parent)
    		return true;

    	Component c = child.getParent();
    	while (c != null) {
    		if (c == parent)
    			return true;
    		c = c.getParent();
    	}

    	return false;
    }

    /**
     * Merge pdfList to outFile
     * @param pdfList
     * @param outFile
     * @throws IOException
     * @throws DocumentException
     * @throws FileNotFoundException
     */
    public static void mergePdf(List<File> pdfList, File outFile) throws IOException,
			DocumentException, FileNotFoundException {
		Util.mergePdf(pdfList, outFile);
   }

    /**
	 *	Get window title
	 *  @param ctx context
	 *  @param WindowNo window
	 *  @return Header String
	 */
	public static String getWindowHeader(Properties ctx, int WindowNo)
	{
		StringBuilder sb = new StringBuilder();
		if (WindowNo > 0){
			sb.append(Env.getContext(ctx, WindowNo, "_WinInfo_WindowName", false)).append("  ");
			final String documentNo = Env.getContext(ctx, WindowNo, "DocumentNo", false);
			final String value = Env.getContext(ctx, WindowNo, "Value", false);
			final String name = Env.getContext(ctx, WindowNo, "Name", false);
			if(!"".equals(documentNo)) {
				sb.append(documentNo).append("  ");
			}
			if(!"".equals(value)) {
				sb.append(value).append("  ");
			}
			if(!"".equals(name)) {
				sb.append(name).append("  ");
			}
		}
		return sb.toString();
	}	//	getHeader

	/**
	 * @param ctx
	 * @return {@link Language}
	 */
	public static Language getLanguage(Properties ctx) {
		return Env.getLocaleLanguage(ctx);
	}

	/**
	 * @param ctx
	 * @return {@link Locale}
	 */
	public static Locale getLocale(Properties ctx) {
		return Env.getLocale(ctx);
	}

	/**
	 * Get title for dialog window
	 * @param ctx
	 * @param windowNo
	 * @param prefix
	 * @return dialog header
	 */
	public static String getDialogHeader(Properties ctx, int windowNo, String prefix) {
		StringBuilder sb = new StringBuilder();
		if (prefix != null)
			sb.append(prefix);
		if (windowNo > 0){
			sb.append(Env.getContext(ctx, windowNo, "_WinInfo_WindowName", false)).append(": ");
			String documentNo = Env.getContext(ctx, windowNo, "DocumentNo", false);
			if (Util.isEmpty(documentNo)) // try first tab
				documentNo = Env.getContext(ctx, windowNo, 0, "DocumentNo", false);
			String value = Env.getContext(ctx, windowNo, "Value", false);
			if (Util.isEmpty(value)) // try first tab
				value = Env.getContext(ctx, windowNo, 0, "Value", false);
			String name = Env.getContext(ctx, windowNo, "Name", false);
			if (Util.isEmpty(name)) // try first tab
				name = Env.getContext(ctx, windowNo, 0, "Name", false);
			if(!"".equals(documentNo)) {
				sb.append(documentNo).append("  ");
			}
			if(!"".equals(value)) {
				sb.append(value).append("  ");
			}
			if(!"".equals(name)) {
				sb.append(name).append("  ");
			}
		}
		String header = sb.toString().trim();
		if (header.length() == 0)
			header = ThemeManager.getBrowserTitle();
		if (header.endsWith(":"))
			header = header.substring(0, header.length()-1);
		return header;
	}

	/**
	 * Call {@link #getDialogHeader(Properties, int, String)}
	 * @param ctx
	 * @param windowNo
	 * @return dialog header
	 */
	public static String getDialogHeader(Properties ctx, int windowNo) {
		return 	getDialogHeader(ctx, windowNo, null);
	}
	
	/**
	 * Execute synchronous task in UI thread.
	 * Use {@link Executions#activate(Desktop)} and {@link Executions#deactivate(Desktop)} pair if current thread is not UI/Listener thread.
	 * @param runnable
	 */
	public static void executeDesktopTask(final Runnable runnable) {
		Desktop desktop = getDesktop();
		ServerPushTemplate template = new ServerPushTemplate(desktop);
		template.execute(new IServerPushCallback() {			
			@Override
			public void updateUI() {
				runnable.run();
			}
		});
	}
	
	/**
	 * Execute asynchronous task in UI thread.
	 * @param runnable
	 */
	public static void executeAsyncDesktopTask(final Runnable runnable) {
		Desktop desktop = getDesktop();
		ServerPushTemplate template = new ServerPushTemplate(desktop);
		template.executeAsync(new IServerPushCallback() {			
			@Override
			public void updateUI() {
				runnable.run();
			}
		});
	}
	
	/**
	 * Get current desktop
	 * @return {@link Desktop}
	 */
	public static Desktop getDesktop() {
		boolean inUIThread = Executions.getCurrent() != null;
		if (inUIThread) {
			return Executions.getCurrent().getDesktop();
		} else {
			WeakReference<Desktop> ref = DesktopRunnable.getThreadLocalDesktop();
			return ref != null ? ref.get() : null;
		}
	}
	
	/**
	 * @deprecated replace by ClientInfo.isMobile()
	 * @return true if running on a tablet
	 */
	@Deprecated(forRemoval = true, since = "11")
	public static boolean isTablet() {
		return ClientInfo.isMobile();
	}
	
	/**
	 * Get AD_Window_ID from windowNo.
	 * @param windowNo
	 * @return AD_Window_ID or {@link Env#adWindowDummyID} (if it is ProcessDialog of InfoWindow)
	 */
	public static int getADWindowID (int windowNo){
		int adWindowID = 0;
		Object  window = SessionManager.getAppDesktop().findWindow(windowNo);
		// case show a process dialog, window is below window of process dialog
		if (window != null && window instanceof ADWindow){
			adWindowID = ((ADWindow)window).getAD_Window_ID();
		}else if (window != null && (window instanceof ProcessDialog || window instanceof InfoWindow)){
			// dummy window is use in case process or infoWindow open in stand-alone window
			// it help we separate case save preference for all window (windowId = 0, null) and case open in stand-alone (windowId = 200054)
			adWindowID = Env.adWindowDummyID;// dummy window
		}
					
		return adWindowID;
	}
	
	/**
	 * 
	 * @param client
	 * @return {@link WTableDirEditor} for Language if client is with IsMultiLingualDocument=Y
	 * @throws Exception
	 */
	public static WTableDirEditor getListDocumentLanguage (MClient client) throws Exception {
		WTableDirEditor fLanguageType = null;
		if (client.isMultiLingualDocument()){
			Lookup lookupLanguage = MLookupFactory.get (Env.getCtx(), 0, 0, DisplayType.TableDir,
					Env.getLanguage(Env.getCtx()), MLanguage.COLUMNNAME_AD_Language_ID, 0, false, 
					" IsActive='Y' AND IsLoginLocale = 'Y' ");
			fLanguageType = new WTableDirEditor(MLanguage.COLUMNNAME_AD_Language_ID, false, false, true, lookupLanguage);
		}
		return fLanguageType;
	}

	private static String m_ApplicationUrl = null;
	
	/** 
	 * @return URL to access application from browser
	 */
	public static String getApplicationUrl() {
		String url = MSysConfig.getValue(MSysConfig.APPLICATION_URL, Env.getAD_Client_ID(Env.getCtx()));
		if (!Util.isEmpty(url) && !url.equals("USE_HARDCODED"))
			return MSysConfig.getValue(MSysConfig.APPLICATION_URL, Env.getAD_Client_ID(Env.getCtx()));
		if (m_ApplicationUrl != null)
			return m_ApplicationUrl;
		int port = Executions.getCurrent().getServerPort();
		String sch = Executions.getCurrent().getScheme();
		String sport = null;
		if ( (sch.equals("http") && port == 80) || (sch.equals("https") && port == 443) )
			sport = "";
		else
			sport = ":" + port;
		m_ApplicationUrl = sch + "://" + Executions.getCurrent().getServerName() + sport + Executions.getCurrent().getContextPath() +  Executions.getCurrent().getDesktop().getRequestPath();
		return m_ApplicationUrl;
	}

	/**
	 * @param po
	 * @return URL link for direct access to the record using AD_Table_ID+Record_ID
	 */
	public static String getZoomUrlTableID(PO po)
	{
		return getApplicationUrl() + "?Action=Zoom&AD_Table_ID=" + po.get_Table_ID() + "&Record_ID=" + po.get_ID();
	}

	/**
	 * @param po
	 * @return URL link for direct access to the record using TableName+Record_ID
	 */
	public static String getZoomUrlTableName(PO po)
	{
		return getApplicationUrl() + "?Action=Zoom&TableName" + po.get_TableName() + "&Record_ID=" + po.get_ID();
	}

	/**
	 * Set attribute value to Boolean.TRUE if attribute doesn't exists in current execution yet. 
	 * @param attribute
	 * @return true if attribute have been set for current executions
	 */
	public static boolean getOrSetExecutionAttribute(String attribute) {
		if (Executions.getCurrent() != null) {
    		if (Executions.getCurrent().getAttribute(attribute) != null)
    			return true;
    		Executions.getCurrent().setAttribute(attribute, Boolean.TRUE);
    	}
    	return false;
	}
	
	/**
	 * Workaround for detached HTML input element leak.
	 * <br/>
	 * Detach all InputElement and Button that's the immediate or not immediate child of parent.
	 * <br/>
	 * Note that to remedy the detached HTML element leak issue, we must defer the detach of parent
	 * with {@link Executions#schedule(Desktop, EventListener, Event)}.
	 * @param parent {@link Component}
	 */
	public static void detachInputElement(Component parent) {
		if (parent instanceof InputElement || parent instanceof Button) {
			parent.detach();
		}
		if (parent.getChildren().size() > 0) {
			Component[] childs = parent.getChildren().toArray(new Component[0]);
			for(Component child : childs) {
				detachInputElement(child);
			}
		}		
	}
}	//	AEnv

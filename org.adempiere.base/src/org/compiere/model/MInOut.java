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
package org.compiere.model;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.base.Core;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.exceptions.DBException;
import org.adempiere.exceptions.NegativeInventoryDisallowedException;
import org.adempiere.exceptions.PeriodClosedException;
import org.adempiere.util.IReservationTracer;
import org.adempiere.util.IReservationTracerFactory;
import org.adempiere.util.ShippingUtil;
import org.compiere.print.MPrintFormat;
import org.compiere.print.ReportEngine;
import org.compiere.process.DocAction;
import org.compiere.process.DocumentEngine;
import org.compiere.process.IDocsPostProcess;
import org.compiere.process.ProcessInfo;
import org.compiere.process.ServerProcessCtl;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.TimeUtil;
import org.compiere.util.Trx;
import org.compiere.util.TrxEventListener;
import org.compiere.util.Util;
import org.compiere.wf.MWFActivity;
import org.compiere.wf.MWorkflow;

/**
 *  Shipment Model
 *
 *  @author Jorg Janke
 *  @version $Id: MInOut.java,v 1.4 2006/07/30 00:51:03 jjanke Exp $
 *
 *  Modifications: Added the RMA functionality (Ashley Ramdass)
 *  @author Karsten Thiemann, Schaeffer AG
 * 			<li>Bug [ 1759431 ] Problems with VCreateFrom
 *  @author victor.perez@e-evolution.com, e-Evolution http://www.e-evolution.com
 * 			<li>FR [ 1948157  ]  Is necessary the reference for document reverse
 * 			<li> FR [ 2520591 ] Support multiples calendar for Org
 *			@see https://sourceforge.net/p/adempiere/feature-requests/631/
 *  @author Armen Rizal, Goodwill Consulting
 * 			<li>BF [ 1745154 ] Cost in Reversing Material Related Docs
 *  @see https://sourceforge.net/p/adempiere/feature-requests/412/
 *  @author Teo Sarca, teo.sarca@gmail.com
 * 			<li>BF [ 2993853 ] Voiding/Reversing Receipt should void confirmations
 * 				https://sourceforge.net/p/adempiere/bugs/2395/
 */
public class MInOut extends X_M_InOut implements DocAction, IDocsPostProcess
{
	/**
	 * 
	 */
	private static final long serialVersionUID = -8699990804131725782L;

	/**
	 * 	Create Shipment From Order
	 *	@param order order
	 *	@param movementDate optional movement date
	 *	@param forceDelivery ignore order delivery rule
	 *	@param allAttributeInstances if true, all attribute set instances
	 *	@param minGuaranteeDate optional minimum guarantee date if all attribute instances
	 *	@param complete complete document (Process if false, Complete if true)
	 *	@param trxName transaction
	 *	@return Shipment or null
	 */
	public static MInOut createFrom (MOrder order, Timestamp movementDate,
			boolean forceDelivery, boolean allAttributeInstances, Timestamp minGuaranteeDate,
			boolean complete, String trxName)
	{		
		if (order == null)
			throw new IllegalArgumentException("No Order");
		//
		if (!forceDelivery && DELIVERYRULE_CompleteLine.equals(order.getDeliveryRule()))
		{
			return null;
		}

		//	Create Header
		MInOut retValue = new MInOut (order, 0, movementDate);
		retValue.setDocAction(complete ? DOCACTION_Complete : DOCACTION_Prepare);

		//	Check if we can create the lines
		MOrderLine[] oLines = order.getLines(true, "M_Product_ID");
		for (int i = 0; i < oLines.length; i++)
		{
			// Calculate how much is left to deliver (ordered - delivered)
			BigDecimal qty = oLines[i].getQtyOrdered().subtract(oLines[i].getQtyDelivered());
			//	Nothing to deliver
			if (qty.signum() == 0)
				continue;
			//	Stock Info
			MStorageOnHand[] storages = null;
			MProduct product = oLines[i].getProduct();
			if (product != null && product.get_ID() != 0 && product.isStocked())
			{
				String MMPolicy = product.getMMPolicy();
				storages = MStorageOnHand.getWarehouse (order.getCtx(), order.getM_Warehouse_ID(),
					oLines[i].getM_Product_ID(), oLines[i].getM_AttributeSetInstance_ID(),
					minGuaranteeDate, MClient.MMPOLICY_FiFo.equals(MMPolicy), true, 0, trxName);
			} else {
				continue;
			}

			if (!forceDelivery)
			{
				BigDecimal maxQty = Env.ZERO;
				for (int ll = 0; ll < storages.length; ll++)
					maxQty = maxQty.add(storages[ll].getQtyOnHand());
				if (DELIVERYRULE_Availability.equals(order.getDeliveryRule()))
				{
					if (maxQty.compareTo(qty) < 0)
						qty = maxQty;
				}
				else if (DELIVERYRULE_CompleteLine.equals(order.getDeliveryRule()))
				{
					if (maxQty.compareTo(qty) < 0)
						continue;
				}
			}
			//	Create Line
			if (retValue.get_ID() == 0)	//	not saved yet
				retValue.saveEx(trxName);
			//	Create a line until qty is reached
			for (int ll = 0; ll < storages.length; ll++)
			{
				BigDecimal lineQty = storages[ll].getQtyOnHand();
				if (lineQty.compareTo(qty) > 0)
					lineQty = qty;
				MInOutLine line = new MInOutLine (retValue);
				line.setOrderLine(oLines[i], storages[ll].getM_Locator_ID(),
					order.isSOTrx() ? lineQty : Env.ZERO);
				line.setQty(lineQty);	//	Correct UOM for QtyEntered
				if (oLines[i].getQtyEntered().compareTo(oLines[i].getQtyOrdered()) != 0)
					line.setQtyEntered(lineQty
						.multiply(oLines[i].getQtyEntered())
						.divide(oLines[i].getQtyOrdered(), 12, RoundingMode.HALF_UP));
				line.setC_Project_ID(oLines[i].getC_Project_ID());
				line.saveEx(trxName);
				//	Delivered everything ?
				qty = qty.subtract(lineQty);

				if (qty.signum() == 0)
					break;
			}
		}	//	for all order lines

		//	No Lines saved
		if (retValue.get_ID() == 0)
			return null;

		return retValue;
		
	}

	/**
	 * 	Create new Shipment by copying
	 * 	@param from shipment
	 * 	@param dateDoc date of the document date
	 * 	@param C_DocType_ID doc type
	 * 	@param isSOTrx sales order
	 * 	@param counter create counter links
	 * 	@param trxName trx
	 * 	@param setOrder set the order link
	 *	@return Shipment
	 */
	public static MInOut copyFrom (MInOut from, Timestamp dateDoc, Timestamp dateAcct,
		int C_DocType_ID, boolean isSOTrx, boolean counter, String trxName, boolean setOrder)
	{
		MInOut to = new MInOut (from.getCtx(), 0, null);
		to.set_TrxName(trxName);
		copyValues(from, to, from.getAD_Client_ID(), from.getAD_Org_ID());
		to.set_ValueNoCheck ("M_InOut_ID", I_ZERO);
		to.set_ValueNoCheck ("DocumentNo", null);
		//
		to.setDocStatus (DOCSTATUS_Drafted);		//	Draft
		to.setDocAction(DOCACTION_Complete);
		//
		to.setC_DocType_ID (C_DocType_ID);
		to.setIsSOTrx(isSOTrx);
		if (counter)
		{
			to.setMovementType();
		}

		//
		to.setDateOrdered (dateDoc);
		to.setDateAcct (dateAcct);
		to.setMovementDate(dateDoc);
		to.setDatePrinted(null);
		to.setIsPrinted (false);
		to.setDateReceived(null);
		to.setNoPackages(0);
		to.setShipDate(null);
		to.setPickDate(null);
		to.setIsInTransit(false);
		//
		to.setIsApproved (false);
		to.setC_Invoice_ID(0);
		to.setTrackingNo(null);
		to.setIsInDispute(false);
		//
		to.setPosted (false);
		to.setProcessed (false);
		//[ 1633721 ] Reverse Documents- Processing=Y
		to.setProcessing(false);
		to.setC_Order_ID(0);	//	Overwritten by setOrder
		to.setM_RMA_ID(0);      //  Overwritten by setOrder
		if (counter)
		{
			to.setC_Order_ID(0);
			to.setRef_InOut_ID(from.getM_InOut_ID());
			//	Try to find Order/Invoice link
			if (from.getC_Order_ID() != 0)
			{
				MOrder peer = new MOrder (from.getCtx(), from.getC_Order_ID(), from.get_TrxName());
				if (peer.getRef_Order_ID() != 0)
					to.setC_Order_ID(peer.getRef_Order_ID());
			}
			if (from.getC_Invoice_ID() != 0)
			{
				MInvoice peer = new MInvoice (from.getCtx(), from.getC_Invoice_ID(), from.get_TrxName());
				if (peer.getRef_Invoice_ID() != 0)
					to.setC_Invoice_ID(peer.getRef_Invoice_ID());
			}
			//find RMA link
			if (from.getM_RMA_ID() != 0)
			{
				MRMA peer = new MRMA (from.getCtx(), from.getM_RMA_ID(), from.get_TrxName());
				if (peer.getRef_RMA_ID() > 0)
					to.setM_RMA_ID(peer.getRef_RMA_ID());
			}
		}
		else
		{
			to.setRef_InOut_ID(0);
			if (setOrder)
			{
				to.setC_Order_ID(from.getC_Order_ID());
				to.setM_RMA_ID(from.getM_RMA_ID()); // Copy also RMA
			}
		}
		//
		if (!to.save(trxName))
			throw new IllegalStateException("Could not create Shipment");
		if (counter)
			from.setRef_InOut_ID(to.getM_InOut_ID());

		if (to.copyLinesFrom(from, counter, setOrder) <= 0)
			throw new IllegalStateException("Could not create Shipment Lines");

		return to;
	}	//	copyFrom

	/**
	 *  @deprecated
	 * 	Create new Shipment by copying
	 * 	@param from shipment
	 * 	@param dateDoc date of the document date
	 * 	@param C_DocType_ID doc type
	 * 	@param isSOTrx sales order
	 * 	@param counter create counter links
	 * 	@param trxName trx
	 * 	@param setOrder set the order link
	 *	@return Shipment
	 */
	public static MInOut copyFrom (MInOut from, Timestamp dateDoc,
		int C_DocType_ID, boolean isSOTrx, boolean counter, String trxName, boolean setOrder)
	{
		MInOut to = copyFrom ( from, dateDoc, dateDoc,
				C_DocType_ID, isSOTrx, counter,
				trxName, setOrder);
		return to;

	}

	/**************************************************************************
	 * 	Standard Constructor
	 *	@param ctx context
	 *	@param M_InOut_ID
	 *	@param trxName rx name
	 */
	public MInOut (Properties ctx, int M_InOut_ID, String trxName)
	{
		this (ctx, M_InOut_ID, trxName, (String[]) null);
	}	//	MInOut

	public MInOut(Properties ctx, int M_InOut_ID, String trxName, String... virtualColumns) {
		super(ctx, M_InOut_ID, trxName, virtualColumns);
		if (M_InOut_ID == 0)
		{
			setIsSOTrx (false);
			setMovementDate (new Timestamp (System.currentTimeMillis ()));
			setDateAcct (getMovementDate());
			setDeliveryRule (DELIVERYRULE_Availability);
			setDeliveryViaRule (DELIVERYVIARULE_Pickup);
			setFreightCostRule (FREIGHTCOSTRULE_FreightIncluded);
			setDocStatus (DOCSTATUS_Drafted);
			setDocAction (DOCACTION_Complete);
			setPriorityRule (PRIORITYRULE_Medium);
			setNoPackages(0);
			setIsInTransit(false);
			setIsPrinted (false);
			setSendEMail (false);
			setIsInDispute(false);
			//
			setIsApproved(false);
			super.setProcessed (false);
			setProcessing(false);
			setPosted(false);
		}
	}

	/**
	 *  Load Constructor
	 *  @param ctx context
	 *  @param rs result set record
	 *	@param trxName transaction
	 */
	public MInOut (Properties ctx, ResultSet rs, String trxName)
	{
		super(ctx, rs, trxName);
	}	//	MInOut

	/**
	 * 	Order Constructor - create header only
	 *	@param order order
	 *	@param movementDate optional movement date (default today)
	 *	@param C_DocTypeShipment_ID document type or 0
	 */
	public MInOut (MOrder order, int C_DocTypeShipment_ID, Timestamp movementDate)
	{
		this (order.getCtx(), 0, order.get_TrxName());
		setClientOrg(order);
		setC_BPartner_ID (order.getC_BPartner_ID());
		setC_BPartner_Location_ID (order.getC_BPartner_Location_ID());	//	shipment address
		setAD_User_ID(order.getAD_User_ID());
		//
		setM_Warehouse_ID (order.getM_Warehouse_ID());
		setIsSOTrx (order.isSOTrx());
		if (C_DocTypeShipment_ID == 0) {
			MDocType dto = MDocType.get(getCtx(), order.getC_DocType_ID());
			C_DocTypeShipment_ID = dto.getC_DocTypeShipment_ID();
			if (C_DocTypeShipment_ID <= 0) 
				throw new AdempiereException("@NotFound@ @C_DocTypeShipment_ID@ - @C_DocType_ID@:"
					 +dto.get_Translation(MDocType.COLUMNNAME_Name));
		}
		setC_DocType_ID (C_DocTypeShipment_ID);

		setMovementType();
		
		//	Default - Today
		if (movementDate != null)
			setMovementDate (movementDate);
		setDateAcct (getMovementDate());

		//	Copy from Order
		setC_Order_ID(order.getC_Order_ID());
		setDeliveryRule (order.getDeliveryRule());
		setDeliveryViaRule (order.getDeliveryViaRule());
		setM_Shipper_ID(order.getM_Shipper_ID());
		setFreightCostRule (order.getFreightCostRule());
		setFreightAmt(order.getFreightAmt());
		setSalesRep_ID(order.getSalesRep_ID());
		//
		setC_Activity_ID(order.getC_Activity_ID());
		setC_Campaign_ID(order.getC_Campaign_ID());
		setC_Charge_ID(order.getC_Charge_ID());
		setChargeAmt(order.getChargeAmt());
		//
		setC_Project_ID(order.getC_Project_ID());
		setDateOrdered(order.getDateOrdered());
		setDescription(order.getDescription());
		setPOReference(order.getPOReference());
		setSalesRep_ID(order.getSalesRep_ID());
		setAD_OrgTrx_ID(order.getAD_OrgTrx_ID());
		setUser1_ID(order.getUser1_ID());
		setUser2_ID(order.getUser2_ID());
		setPriorityRule(order.getPriorityRule());
		// Drop shipment
		setIsDropShip(order.isDropShip());
		setDropShip_BPartner_ID(order.getDropShip_BPartner_ID());
		setDropShip_Location_ID(order.getDropShip_Location_ID());
		setDropShip_User_ID(order.getDropShip_User_ID());
	}	//	MInOut

	/**
	 * 	Invoice Constructor - create header only
	 *	@param invoice invoice
	 *	@param C_DocTypeShipment_ID document type or 0
	 *	@param movementDate optional movement date (default today)
	 *	@param M_Warehouse_ID warehouse
	 */
	public MInOut (MInvoice invoice, int C_DocTypeShipment_ID, Timestamp movementDate, int M_Warehouse_ID)
	{
		this (invoice.getCtx(), 0, invoice.get_TrxName());
		setClientOrg(invoice);
		setC_BPartner_ID (invoice.getC_BPartner_ID());
		setC_BPartner_Location_ID (invoice.getC_BPartner_Location_ID());	//	shipment address
		setAD_User_ID(invoice.getAD_User_ID());
		//
		setM_Warehouse_ID (M_Warehouse_ID);
		setIsSOTrx (invoice.isSOTrx());
		setMovementType (invoice.isSOTrx() ? MOVEMENTTYPE_CustomerShipment : MOVEMENTTYPE_VendorReceipts);
		MOrder order = null;
		if (invoice.getC_Order_ID() != 0)
			order = new MOrder (invoice.getCtx(), invoice.getC_Order_ID(), invoice.get_TrxName());
		if (C_DocTypeShipment_ID == 0 && order != null)
			C_DocTypeShipment_ID = DB.getSQLValue(null,
				"SELECT C_DocTypeShipment_ID FROM C_DocType WHERE C_DocType_ID=?",
				order.getC_DocType_ID());
		if (C_DocTypeShipment_ID != 0)
			setC_DocType_ID (C_DocTypeShipment_ID);
		else
			setC_DocType_ID();

		//	Default - Today
		if (movementDate != null)
			setMovementDate (movementDate);
		setDateAcct (getMovementDate());

		//	Copy from Invoice
		setC_Order_ID(invoice.getC_Order_ID());
		setSalesRep_ID(invoice.getSalesRep_ID());
		//
		setC_Activity_ID(invoice.getC_Activity_ID());
		setC_Campaign_ID(invoice.getC_Campaign_ID());
		setC_Charge_ID(invoice.getC_Charge_ID());
		setChargeAmt(invoice.getChargeAmt());
		//
		setC_Project_ID(invoice.getC_Project_ID());
		setDateOrdered(invoice.getDateOrdered());
		setDescription(invoice.getDescription());
		setPOReference(invoice.getPOReference());
		setAD_OrgTrx_ID(invoice.getAD_OrgTrx_ID());
		setUser1_ID(invoice.getUser1_ID());
		setUser2_ID(invoice.getUser2_ID());

		if (order != null)
		{
			setDeliveryRule (order.getDeliveryRule());
			setDeliveryViaRule (order.getDeliveryViaRule());
			setM_Shipper_ID(order.getM_Shipper_ID());
			setFreightCostRule (order.getFreightCostRule());
			setFreightAmt(order.getFreightAmt());

			// Drop Shipment
			setIsDropShip(order.isDropShip());
			setDropShip_BPartner_ID(order.getDropShip_BPartner_ID());
			setDropShip_Location_ID(order.getDropShip_Location_ID());
			setDropShip_User_ID(order.getDropShip_User_ID());
		}
	}	//	MInOut

	/**
	 * 	Copy Constructor - create header only
	 *	@param original original
	 *	@param movementDate optional movement date (default today)
	 *	@param C_DocTypeShipment_ID document type or 0
	 */
	public MInOut (MInOut original, int C_DocTypeShipment_ID, Timestamp movementDate)
	{
		this (original.getCtx(), 0, original.get_TrxName());
		setClientOrg(original);
		setC_BPartner_ID (original.getC_BPartner_ID());
		setC_BPartner_Location_ID (original.getC_BPartner_Location_ID());	//	shipment address
		setAD_User_ID(original.getAD_User_ID());
		//
		setM_Warehouse_ID (original.getM_Warehouse_ID());
		setIsSOTrx (original.isSOTrx());
		setMovementType (original.getMovementType());
		if (C_DocTypeShipment_ID == 0)
			setC_DocType_ID(original.getC_DocType_ID());
		else
			setC_DocType_ID (C_DocTypeShipment_ID);

		//	Default - Today
		if (movementDate != null)
			setMovementDate (movementDate);
		setDateAcct (getMovementDate());

		//	Copy from Order
		setC_Order_ID(original.getC_Order_ID());
		setDeliveryRule (original.getDeliveryRule());
		setDeliveryViaRule (original.getDeliveryViaRule());
		setM_Shipper_ID(original.getM_Shipper_ID());
		setFreightCostRule (original.getFreightCostRule());
		setFreightAmt(original.getFreightAmt());
		setSalesRep_ID(original.getSalesRep_ID());
		//
		setC_Activity_ID(original.getC_Activity_ID());
		setC_Campaign_ID(original.getC_Campaign_ID());
		setC_Charge_ID(original.getC_Charge_ID());
		setChargeAmt(original.getChargeAmt());
		//
		setC_Project_ID(original.getC_Project_ID());
		setDateOrdered(original.getDateOrdered());
		setDescription(original.getDescription());
		setPOReference(original.getPOReference());
		setSalesRep_ID(original.getSalesRep_ID());
		setAD_OrgTrx_ID(original.getAD_OrgTrx_ID());
		setUser1_ID(original.getUser1_ID());
		setUser2_ID(original.getUser2_ID());

		// DropShipment
		setIsDropShip(original.isDropShip());
		setDropShip_BPartner_ID(original.getDropShip_BPartner_ID());
		setDropShip_Location_ID(original.getDropShip_Location_ID());
		setDropShip_User_ID(original.getDropShip_User_ID());

	}	//	MInOut


	/**	Lines					*/
	protected MInOutLine[]	m_lines = null;
	/** Confirmations			*/
	protected MInOutConfirm[]	m_confirms = null;
	/** BPartner				*/
	protected MBPartner		m_partner = null;


	/**
	 * 	Get Document Status
	 *	@return Document Status Clear Text
	 */
	public String getDocStatusName()
	{
		return MRefList.getListName(getCtx(), 131, getDocStatus());
	}	//	getDocStatusName

	/**
	 * 	Add to Description
	 *	@param description text
	 */
	public void addDescription (String description)
	{
		String desc = getDescription();
		if (desc == null)
			setDescription(description);
		else{
			StringBuilder msgd = new StringBuilder(desc).append(" | ").append(description);
			setDescription(msgd.toString());
		}	
	}	//	addDescription

	/**
	 *	String representation
	 *	@return info
	 */
	public String toString ()
	{
		StringBuilder sb = new StringBuilder ("MInOut[")
			.append (get_ID()).append("-").append(getDocumentNo())
			.append(",DocStatus=").append(getDocStatus())
			.append ("]");
		return sb.toString ();
	}	//	toString

	/**
	 * 	Get Document Info
	 *	@return document info (untranslated)
	 */
	public String getDocumentInfo()
	{
		MDocType dt = MDocType.get(getCtx(), getC_DocType_ID());
		StringBuilder msgreturn = new StringBuilder().append(dt.getNameTrl()).append(" ").append(getDocumentNo());
		return msgreturn.toString();
	}	//	getDocumentInfo

	/**
	 * 	Create PDF
	 *	@return File or null
	 */
	public File createPDF ()
	{
		try
		{
			StringBuilder msgfile = new StringBuilder().append(get_TableName()).append(get_ID()).append("_");
			File temp = File.createTempFile(msgfile.toString(), ".pdf");
			return createPDF (temp);
		}
		catch (Exception e)
		{
			log.severe("Could not create PDF - " + e.getMessage());
		}
		return null;
	}	//	getPDF

	/**
	 * 	Create PDF file
	 *	@param file output file
	 *	@return file if success
	 */
	public File createPDF (File file)
	{
		ReportEngine re = ReportEngine.get (getCtx(), ReportEngine.SHIPMENT, getM_InOut_ID(), get_TrxName());
		if (re == null)
			return null;
		MPrintFormat format = re.getPrintFormat();
		// We have a Jasper Print Format
		// ==============================
		if(format.getJasperProcess_ID() > 0)	
		{
			ProcessInfo pi = new ProcessInfo ("", format.getJasperProcess_ID());
			pi.setRecord_ID ( getM_InOut_ID() );
			pi.setIsBatch(true);
			
			ServerProcessCtl.process(pi, null);
			
			return pi.getPDFReport();
		}
		// Standard Print Format (Non-Jasper)
		// ==================================
		return re.getPDF(file);
	}	//	createPDF

	/**
	 * 	Get Lines of Shipment
	 * 	@param requery refresh from db
	 * 	@return lines
	 */
	public MInOutLine[] getLines (boolean requery)
	{
		if (m_lines != null && !requery) {
			set_TrxName(m_lines, get_TrxName());
			return m_lines;
		}
		List<MInOutLine> list = new Query(getCtx(), I_M_InOutLine.Table_Name, "M_InOut_ID=?", get_TrxName())
		.setParameters(getM_InOut_ID())
		.setOrderBy(MInOutLine.COLUMNNAME_Line+","+MInOutLine.COLUMNNAME_M_InOutLine_ID)
		.list();
		//
		m_lines = new MInOutLine[list.size()];
		list.toArray(m_lines);
		return m_lines;
	}	//	getMInOutLines

	/**
	 * 	Get Lines of Shipment
	 * 	@return lines
	 */
	public MInOutLine[] getLines()
	{
		return getLines(false);
	}	//	getLines


	/**
	 * 	Get Confirmations
	 * 	@param requery requery
	 *	@return array of Confirmations
	 */
	public MInOutConfirm[] getConfirmations(boolean requery)
	{
		if (m_confirms != null && !requery)
		{
			set_TrxName(m_confirms, get_TrxName());
			return m_confirms;
		}
		List<MInOutConfirm> list = new Query(getCtx(), I_M_InOutConfirm.Table_Name, "M_InOut_ID=?", get_TrxName())
		.setParameters(getM_InOut_ID())
		.list();
		m_confirms = new MInOutConfirm[list.size ()];
		list.toArray (m_confirms);
		return m_confirms;
	}	//	getConfirmations


	/**
	 * 	Copy Lines From other Shipment
	 *	@param otherShipment shipment
	 *	@param counter set counter info
	 *	@param setOrder set order link
	 *	@return number of lines copied
	 */
	public int copyLinesFrom (MInOut otherShipment, boolean counter, boolean setOrder)
	{
		if (isProcessed() || isPosted() || otherShipment == null)
			return 0;
		MInOutLine[] fromLines = otherShipment.getLines(false);
		int count = 0;
		for (int i = 0; i < fromLines.length; i++)
		{
			MInOutLine line = new MInOutLine (this);
			MInOutLine fromLine = fromLines[i];
			line.set_TrxName(get_TrxName());
			if (counter)	//	header
				PO.copyValues(fromLine, line, getAD_Client_ID(), getAD_Org_ID());
			else
				PO.copyValues(fromLine, line, fromLine.getAD_Client_ID(), fromLine.getAD_Org_ID());
			line.setM_InOut_ID(getM_InOut_ID());
			line.set_ValueNoCheck ("M_InOutLine_ID", I_ZERO);	//	new
			//	Reset
			if (!setOrder)
			{
				line.setC_OrderLine_ID(0);
				line.setM_RMALine_ID(0);  // Reset RMA Line
			}
			if (!counter)
				line.setM_AttributeSetInstance_ID(0);

			line.setRef_InOutLine_ID(0);
			line.setIsInvoiced(false);
			//
			line.setConfirmedQty(Env.ZERO);
			line.setPickedQty(Env.ZERO);
			line.setScrappedQty(Env.ZERO);
			line.setTargetQty(Env.ZERO);
			//	Set Locator based on header Warehouse
			if (getM_Warehouse_ID() != otherShipment.getM_Warehouse_ID())
			{
				line.setM_Locator_ID(0);
				line.setM_Locator_ID(Env.ZERO);
			}
			//
			if (counter)
			{
				line.setRef_InOutLine_ID(fromLine.getM_InOutLine_ID());
				if (fromLine.getC_OrderLine_ID() != 0)
				{
					MOrderLine peer = new MOrderLine (getCtx(), fromLine.getC_OrderLine_ID(), get_TrxName());
					if (peer.getRef_OrderLine_ID() != 0)
						line.setC_OrderLine_ID(peer.getRef_OrderLine_ID());
				}
				//RMALine link
				if (fromLine.getM_RMALine_ID() != 0)
				{
					MRMALine peer = new MRMALine (getCtx(), fromLine.getM_RMALine_ID(), get_TrxName());
					if (peer.getRef_RMALine_ID() > 0)
						line.setM_RMALine_ID(peer.getRef_RMALine_ID());
				}
			}
			
			//
			line.setProcessed(false);
			if (line.save(get_TrxName()))
				count++;
			//	Cross Link
			if (counter)
			{
				fromLine.setRef_InOutLine_ID(line.getM_InOutLine_ID());
				fromLine.saveEx(get_TrxName());
			}
		}
		if (fromLines.length != count) {
			log.log(Level.SEVERE, "Line difference - From=" + fromLines.length + " <> Saved=" + count);
			count = -1; // caller must validate error in count and rollback accordingly - BF [3160928]
		}
		return count;
	}	//	copyLinesFrom

	/** Reversal Flag		*/
	protected boolean m_reversal = false;

	/**
	 * 	Set Reversal
	 *	@param reversal reversal
	 */
	protected void setReversal(boolean reversal)
	{
		m_reversal = reversal;
	}	//	setReversal
	/**
	 * 	Is Reversal
	 *	@return reversal
	 */
	public boolean isReversal()
	{
		return m_reversal;
	}	//	isReversal

	/**
	 * 	Set Processed.
	 * 	Propagate to Lines/Taxes
	 *	@param processed processed
	 */
	public void setProcessed (boolean processed)
	{
		super.setProcessed (processed);
		if (get_ID() == 0)
			return;
		StringBuilder sql = new StringBuilder("UPDATE M_InOutLine SET Processed='")
			.append((processed ? "Y" : "N"))
			.append("' WHERE M_InOut_ID=").append(getM_InOut_ID());
		int noLine = DB.executeUpdate(sql.toString(), get_TrxName());
		m_lines = null;
		if (log.isLoggable(Level.FINE)) log.fine(processed + " - Lines=" + noLine);
	}	//	setProcessed

	/**
	 * 	Get BPartner
	 *	@return partner
	 */
	public MBPartner getBPartner()
	{
		if (m_partner == null)
			m_partner = new MBPartner (getCtx(), getC_BPartner_ID(), get_TrxName());
		return m_partner;
	}	//	getPartner

	/**
	 * 	Set Document Type
	 * 	@param DocBaseType doc type MDocType.DOCBASETYPE_
	 */
	public void setC_DocType_ID (String DocBaseType)
	{
		String sql = "SELECT C_DocType_ID FROM C_DocType "
			+ "WHERE AD_Client_ID=? AND DocBaseType=?"
			+ " AND IsActive='Y'"
			+ " AND IsSOTrx='" + (isSOTrx() ? "Y" : "N") + "' "
			+ "ORDER BY IsDefault DESC";
		int C_DocType_ID = DB.getSQLValue(null, sql, getAD_Client_ID(), DocBaseType);
		if (C_DocType_ID <= 0)
			log.log(Level.SEVERE, "Not found for AC_Client_ID="
				+ getAD_Client_ID() + " - " + DocBaseType);
		else
		{
			if (log.isLoggable(Level.FINE)) log.fine("DocBaseType=" + DocBaseType + " - C_DocType_ID=" + C_DocType_ID);
			setC_DocType_ID (C_DocType_ID);
			boolean isSOTrx = MDocType.DOCBASETYPE_MaterialDelivery.equals(DocBaseType);
			setIsSOTrx (isSOTrx);
		}
	}	//	setC_DocType_ID

	/**
	 * 	Set Default C_DocType_ID.
	 * 	Based on SO flag
	 */
	public void setC_DocType_ID()
	{
		if (isSOTrx())
			setC_DocType_ID(MDocType.DOCBASETYPE_MaterialDelivery);
		else
			setC_DocType_ID(MDocType.DOCBASETYPE_MaterialReceipt);
	}	//	setC_DocType_ID

	/**
	 * 	Set Business Partner Defaults and Details
	 * 	@param bp business partner
	 */
	public void setBPartner (MBPartner bp)
	{
		if (bp == null)
			return;

		setC_BPartner_ID(bp.getC_BPartner_ID());

		//	Set Locations
		MBPartnerLocation[] locs = bp.getLocations(false);
		if (locs != null)
		{
			for (int i = 0; i < locs.length; i++)
			{
				if (locs[i].isShipTo())
					setC_BPartner_Location_ID(locs[i].getC_BPartner_Location_ID());
			}
			//	set to first if not set
			if (getC_BPartner_Location_ID() == 0 && locs.length > 0)
				setC_BPartner_Location_ID(locs[0].getC_BPartner_Location_ID());
		}
		if (getC_BPartner_Location_ID() == 0)
			log.log(Level.SEVERE, "Has no To Address: " + bp);

		//	Set Contact
		MUser[] contacts = bp.getContacts(false);
		if (contacts != null && contacts.length > 0)	//	get first User
			setAD_User_ID(contacts[0].getAD_User_ID());
	}	//	setBPartner

	/**
	 * 	Create the missing next Confirmation
	 */
	public void createConfirmation()
	{
		MDocType dt = MDocType.get(getCtx(), getC_DocType_ID());
		boolean pick = dt.isPickQAConfirm();
		boolean ship = dt.isShipConfirm();
		//	Nothing to do
		if (!pick && !ship)
		{
			log.fine("No need");
			return;
		}

		//	Create Both .. after each other
		if (pick && ship)
		{
			boolean havePick = false;
			boolean haveShip = false;
			MInOutConfirm[] confirmations = getConfirmations(false);
			for (int i = 0; i < confirmations.length; i++)
			{
				MInOutConfirm confirm = confirmations[i];
				if (MInOutConfirm.CONFIRMTYPE_PickQAConfirm.equals(confirm.getConfirmType()))
				{
					if (!confirm.isProcessed())		//	wait intil done
					{
						if (log.isLoggable(Level.FINE)) log.fine("Unprocessed: " + confirm);
						return;
					}
					havePick = true;
				}
				else if (MInOutConfirm.CONFIRMTYPE_ShipReceiptConfirm.equals(confirm.getConfirmType()))
					haveShip = true;
			}
			//	Create Pick
			if (!havePick)
			{
				MInOutConfirm.create (this, MInOutConfirm.CONFIRMTYPE_PickQAConfirm, false);
				return;
			}
			//	Create Ship
			if (!haveShip)
			{
				MInOutConfirm.create (this, MInOutConfirm.CONFIRMTYPE_ShipReceiptConfirm, false);
				return;
			}
			return;
		}
		//	Create just one
		if (pick)
			MInOutConfirm.create (this, MInOutConfirm.CONFIRMTYPE_PickQAConfirm, true);
		else if (ship)
			MInOutConfirm.create (this, MInOutConfirm.CONFIRMTYPE_ShipReceiptConfirm, true);
	}	//	createConfirmation
	
	protected void voidConfirmations()
	{
		for(MInOutConfirm confirm : getConfirmations(true))
		{
			if (!confirm.isProcessed())
			{
				if (!confirm.processIt(MInOutConfirm.DOCACTION_Void))
					throw new AdempiereException(confirm.getProcessMsg());
				confirm.saveEx();
			}
		}
	}


	/**
	 * 	Set Warehouse and check/set Organization
	 *	@param M_Warehouse_ID id
	 */
	public void setM_Warehouse_ID (int M_Warehouse_ID)
	{
		if (M_Warehouse_ID == 0)
		{
			log.severe("Ignored - Cannot set AD_Warehouse_ID to 0");
			return;
		}
		super.setM_Warehouse_ID (M_Warehouse_ID);
		//
		MWarehouse wh = MWarehouse.get(getCtx(), getM_Warehouse_ID());
		if (wh.getAD_Org_ID() != getAD_Org_ID())
		{
			log.warning("M_Warehouse_ID=" + M_Warehouse_ID
				+ ", Overwritten AD_Org_ID=" + getAD_Org_ID() + "->" + wh.getAD_Org_ID());
			setAD_Org_ID(wh.getAD_Org_ID());
		}
	}	//	setM_Warehouse_ID

	/**
	 * Gets Movement Type based on Document Type's DocBaseType and isSOTrx
	 * @param ctx 
	 * @param C_DocType_ID Document Type ID
	 * @param issotrx is sales transaction
	 * @param trxName transaction name
	 * @return Movement Type
	 */
	public static String getMovementType(Properties ctx, int C_DocType_ID, boolean issotrx, String trxName) {
		String movementType = null;
		MDocType docType = MDocType.get(C_DocType_ID);
		
		if (docType == null) return null;
		
        if (docType.getDocBaseType().equals(MDocType.DOCBASETYPE_MaterialDelivery)) 
            movementType = docType.isSOTrx() ? MOVEMENTTYPE_CustomerShipment : MOVEMENTTYPE_VendorReturns; 
        else if (docType.getDocBaseType().equals(MDocType.DOCBASETYPE_MaterialReceipt)) 
            movementType = docType.isSOTrx() ? MOVEMENTTYPE_CustomerReturns : MOVEMENTTYPE_VendorReceipts;  
        
		return movementType;
	}

	/**
	 * Sets Movement Type based on Document Type's DocBaseType and isSOTrx
	 */
	public void setMovementType() {
		
		if(getC_DocType_ID() <= 0) {
			log.saveError("FillMandatory", Msg.translate(getCtx(), "C_DocType_ID"));
			return;
		}
		
		String movementType = getMovementType(getCtx(), getC_DocType_ID(), isSOTrx(), get_TrxName());
        setMovementType(movementType); 
	}
	
	/**
	 * 	Before Save
	 *	@param newRecord new
	 *	@return true or false
	 */
	protected boolean beforeSave (boolean newRecord)
	{
		if(newRecord || is_ValueChanged("C_DocType_ID")) {
			setMovementType();
		}
		
		MWarehouse wh = MWarehouse.get(getCtx(), getM_Warehouse_ID());
		//	Warehouse Org
		if (newRecord)
		{
			if (wh.getAD_Org_ID() != getAD_Org_ID())
			{
				log.saveError("WarehouseOrgConflict", "");
				return false;
			}
		}

		boolean disallowNegInv = wh.isDisallowNegativeInv();
		String DeliveryRule = getDeliveryRule();
		if((disallowNegInv && DELIVERYRULE_Force.equals(DeliveryRule)) ||
				(DeliveryRule == null || DeliveryRule.length()==0))
			setDeliveryRule(DELIVERYRULE_Availability);

        // Shipment/Receipt can have either Order/RMA (For Movement type)
        if (getC_Order_ID() != 0 && getM_RMA_ID() != 0)
        {
            log.saveError("OrderOrRMA", "");
            return false;
        }

        if (isSOTrx() && getM_RMA_ID() != 0)
        {
            // Set Document and Movement type for this Receipt
            MRMA rma = new MRMA(getCtx(), getM_RMA_ID(), get_TrxName());
            MDocType docType = MDocType.get(getCtx(), rma.getC_DocType_ID());
            setC_DocType_ID(docType.getC_DocTypeShipment_ID());
        }
                
        if (newRecord && isSOTrx())
        {
        	if (MInOut.FREIGHTCOSTRULE_CustomerAccount.equals(getFreightCostRule()))
    		{
        		if (Util.isEmpty(getShipperAccount()))
        		{
        			String shipperAccount = ShippingUtil.getBPShipperAccount(getM_Shipper_ID(), getC_BPartner_ID(), getC_BPartner_Location_ID(), getAD_Org_ID(), get_TrxName());
        			setShipperAccount(shipperAccount);
        		}
        		
        		if (Util.isEmpty(getFreightCharges()))
        			setFreightCharges(MInOut.FREIGHTCHARGES_Collect);
    		}
        }

		return true;
	}	//	beforeSave

	/**
	 * 	After Save
	 *	@param newRecord new
	 *	@param success success
	 *	@return success
	 */
	protected boolean afterSave (boolean newRecord, boolean success)
	{
		if (!success || newRecord)
			return success;

		if (is_ValueChanged("AD_Org_ID"))
		{
			final String sql = "UPDATE M_InOutLine ol"
					+ " SET AD_Org_ID ="
					+ "(SELECT AD_Org_ID"
					+ " FROM M_InOut o WHERE ol.M_InOut_ID=o.M_InOut_ID) "
					+ "WHERE M_InOut_ID=?";
			int no = DB.executeUpdateEx(sql, new Object[] {getM_InOut_ID()}, get_TrxName());
			if (log.isLoggable(Level.FINE)) log.fine("Lines -> #" + no);
		}
		return true;
	}	//	afterSave


	/**************************************************************************
	 * 	Process document
	 *	@param processAction document action
	 *	@return true if performed
	 */
	public boolean processIt (String processAction)
	{
		m_processMsg = null;
		DocumentEngine engine = new DocumentEngine (this, getDocStatus());
		return engine.processIt (processAction, getDocAction());
	}	//	process

	/**	Process Message 			*/
	protected String		m_processMsg = null;
	/**	Just Prepared Flag			*/
	protected boolean		m_justPrepared = false;

	/**
	 * 	Unlock Document.
	 * 	@return true if success
	 */
	public boolean unlockIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		setProcessing(false);
		return true;
	}	//	unlockIt

	/**
	 * 	Invalidate Document
	 * 	@return true if success
	 */
	public boolean invalidateIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		setDocAction(DOCACTION_Prepare);
		return true;
	}	//	invalidateIt

	/**
	 *	Prepare Document
	 * 	@return new status (In Progress or Invalid)
	 */
	public String prepareIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;

		MDocType dt = MDocType.get(getCtx(), getC_DocType_ID());

		//  Order OR RMA can be processed on a shipment/receipt
		if (getC_Order_ID() != 0 && getM_RMA_ID() != 0)
		{
		    m_processMsg = "@OrderOrRMA@";
		    return DocAction.STATUS_Invalid;
		}
		//	Std Period open?
		if (!MPeriod.isOpen(getCtx(), getDateAcct(), dt.getDocBaseType(), getAD_Org_ID()))
		{
			m_processMsg = "@PeriodClosed@";
			return DocAction.STATUS_Invalid;
		}

		// Validate Close Order
		if (!isReversal())
		{
			StringBuilder sql = new StringBuilder("SELECT DISTINCT o.DocumentNo FROM M_InOut io ")
					.append("JOIN M_InOutLine iol ON (io.M_InOut_ID=iol.M_InOut_ID) ")
					.append("JOIN C_OrderLine ol ON (iol.C_OrderLine_ID=ol.C_OrderLine_ID) ")
					.append("JOIN C_Order o ON (ol.C_Order_ID=o.C_Order_ID) ")
					.append("WHERE o.DocStatus='CL' AND (ol.M_Product_ID > 0 OR ol.C_Charge_ID > 0) AND iol.MovementQty != 0 ")
					.append("AND ol.IsActive='Y' AND iol.IsActive='Y' ")
					.append("AND io.M_InOut_ID=? ");
			List<List<Object>> closeOrders = DB.getSQLArrayObjectsEx(get_TrxName(), sql.toString(), getM_InOut_ID());
			if (closeOrders != null && closeOrders.size() > 0) 
			{
				m_processMsg = Msg.getMsg(p_ctx,"OrderClosed")+" (";
				for(int i = 0; i< closeOrders.size(); i++)
				{
					if (i > 0)
						m_processMsg += ", ";
					m_processMsg += closeOrders.get(i).get(0).toString();
				}
				m_processMsg += ")";
				return DocAction.STATUS_Invalid;
			}
		}
				
		//	Credit Check
		if (isSOTrx() && !isReversal() && !isCustomerReturn())
		{
			I_C_Order order = getC_Order();
			if (order != null && MDocType.DOCSUBTYPESO_PrepayOrder.equals(order.getC_DocType().getDocSubTypeSO())
					&& !MSysConfig.getBooleanValue(MSysConfig.CHECK_CREDIT_ON_PREPAY_ORDER, true, getAD_Client_ID(), getAD_Org_ID())) {
				// ignore -- don't validate Prepay Orders depending on sysconfig parameter
			} else {
				MBPartner bp = new MBPartner (getCtx(), getC_BPartner_ID(), get_TrxName());
				if (MBPartner.SOCREDITSTATUS_CreditStop.equals(bp.getSOCreditStatus()))
				{
					m_processMsg = "@BPartnerCreditStop@ - @TotalOpenBalance@="
						+ bp.getTotalOpenBalance()
						+ ", @SO_CreditLimit@=" + bp.getSO_CreditLimit();
					return DocAction.STATUS_Invalid;
				}
				if (MBPartner.SOCREDITSTATUS_CreditHold.equals(bp.getSOCreditStatus()))
				{
					m_processMsg = "@BPartnerCreditHold@ - @TotalOpenBalance@="
						+ bp.getTotalOpenBalance()
						+ ", @SO_CreditLimit@=" + bp.getSO_CreditLimit();
					return DocAction.STATUS_Invalid;
				}
				if (!MBPartner.SOCREDITSTATUS_NoCreditCheck.equals(bp.getSOCreditStatus())
						&& Env.ZERO.compareTo(bp.getSO_CreditLimit()) != 0)
				{
					BigDecimal notInvoicedAmt = MBPartner.getNotInvoicedAmt(getC_BPartner_ID());
					if (MBPartner.SOCREDITSTATUS_CreditHold.equals(bp.getSOCreditStatus(notInvoicedAmt)))
					{
						m_processMsg = "@BPartnerOverSCreditHold@ - @TotalOpenBalance@="
							+ bp.getTotalOpenBalance() + ", @NotInvoicedAmt@=" + notInvoicedAmt
							+ ", @SO_CreditLimit@=" + bp.getSO_CreditLimit();
						return DocAction.STATUS_Invalid;
					}
				}
			}
		}

		//	Lines
		MInOutLine[] lines = getLines(true);
		if (lines == null || lines.length == 0)
		{
			m_processMsg = "@NoLines@";
			return DocAction.STATUS_Invalid;
		}
		BigDecimal Volume = Env.ZERO;
		BigDecimal Weight = Env.ZERO;

		//	Mandatory Attributes
		for (int i = 0; i < lines.length; i++)
		{
			MInOutLine line = lines[i];
			MProduct product = line.getProduct();
			if (product != null)
			{
				Volume = Volume.add(product.getVolume().multiply(line.getMovementQty()));
				Weight = Weight.add(product.getWeight().multiply(line.getMovementQty()));
			}
			//
			if (line.getM_AttributeSetInstance_ID() != 0)
				continue;
			if (product != null && product.isASIMandatoryFor(MAttributeSet.MANDATORYTYPE_WhenShipping,isSOTrx()))
			{
				if (product.getAttributeSet() != null && !product.getAttributeSet().excludeTableEntry(MInOutLine.Table_ID, isSOTrx())) {
					BigDecimal qtyDiff = line.getMovementQty();
					// verify if the ASIs are captured on lineMA
					MInOutLineMA mas[] = MInOutLineMA.get(getCtx(),
							line.getM_InOutLine_ID(), get_TrxName());
					BigDecimal qtyma = Env.ZERO;
					for (MInOutLineMA ma : mas) {
						if (! ma.isAutoGenerated()) {
							qtyma = qtyma.add(ma.getMovementQty());
						}
					}
					if (qtyma.subtract(qtyDiff).signum() != 0) {
						m_processMsg = "@M_AttributeSet_ID@ @IsMandatory@ (@Line@ #" + lines[i].getLine() +
								", @M_Product_ID@=" + product.getValue() + ")";
						return DocAction.STATUS_Invalid;
					}
				}
			}
		}
		setVolume(Volume);
		setWeight(Weight);

		if (!isReversal())	//	don't change reversal
		{
			createConfirmation();
		}

		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;

		m_justPrepared = true;
		if (!DOCACTION_Complete.equals(getDocAction()))
			setDocAction(DOCACTION_Complete);
		return DocAction.STATUS_InProgress;
	}	//	prepareIt

	/**
	 * Check if Document is Customer Return.
	 * @return True if Document is Customer Return
	 */
	private boolean isCustomerReturn() {
		MDocType doctype = MDocType.get(getC_DocType_ID());
		if(isSOTrx() && doctype.getDocBaseType().equals("MMR") && doctype.isSOTrx())
			return true;
		return false;
	}

	/**
	 * 	Approve Document
	 * 	@return true if success
	 */
	public boolean  approveIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		setIsApproved(true);
		return true;
	}	//	approveIt

	/**
	 * 	Reject Approval
	 * 	@return true if success
	 */
	public boolean rejectIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		setIsApproved(false);
		return true;
	}	//	rejectIt

	/**
	 * 	Complete Document
	 * 	@return new status (Complete, In Progress, Invalid, Waiting ..)
	 */
	public String completeIt()
	{
		//	Re-Check
		if (!m_justPrepared)
		{
			String status = prepareIt();
			m_justPrepared = false;
			if (!DocAction.STATUS_InProgress.equals(status))
				return status;
		}

		// Set the definite document number after completed (if needed)
		setDefiniteDocumentNo();

		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_COMPLETE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;

		if (pendingCustomerConfirmations()) {
			m_processMsg = "@Open@: @M_InOutConfirm_ID@";
			return DocAction.STATUS_InProgress;
		}

		//	Implicit Approval
		if (!isApproved())
			approveIt();
		if (log.isLoggable(Level.INFO)) log.info(toString());
		StringBuilder info = new StringBuilder();

		StringBuilder errors = new StringBuilder();
		//	For all lines
		MInOutLine[] lines = getLines(false);
		for (int lineIndex = 0; lineIndex < lines.length; lineIndex++)
		{
			MInOutLine sLine = lines[lineIndex];
			MProduct product = sLine.getProduct();

			try
			{
				//	Qty & Type
				String MovementType = getMovementType();
				BigDecimal Qty = sLine.getMovementQty();
				if (MovementType.charAt(1) == '-')	//	C- Customer Shipment - V- Vendor Return
					Qty = Qty.negate();
	
				//	Update Order Line
				MOrderLine oLine = null;
				if (sLine.getC_OrderLine_ID() != 0)
				{
					oLine = new MOrderLine (getCtx(), sLine.getC_OrderLine_ID(), get_TrxName());
					if (log.isLoggable(Level.FINE)) log.fine("OrderLine - Reserved=" + oLine.getQtyReserved()
						+ ", Delivered=" + oLine.getQtyDelivered());
				}
	
	
	            // Load RMA Line
	            MRMALine rmaLine = null;
	
	            if (sLine.getM_RMALine_ID() != 0)
	            {
	                rmaLine = new MRMALine(getCtx(), sLine.getM_RMALine_ID(), get_TrxName());
	            }
	
				if (log.isLoggable(Level.INFO)) log.info("Line=" + sLine.getLine() + " - Qty=" + sLine.getMovementQty());
	
				//	Stock Movement - Counterpart MOrder.reserveStock
				if (product != null
					&& product.isStocked() )
				{
					//Ignore the Material Policy when is Reverse Correction
					if(!isReversal())
					{
						BigDecimal movementQty = sLine.getMovementQty();
						BigDecimal qtyOnLineMA = MInOutLineMA.getManualQty(sLine.getM_InOutLine_ID(), get_TrxName());
	
						if (   (movementQty.signum() != 0 && qtyOnLineMA.signum() != 0 && movementQty.signum() != qtyOnLineMA.signum()) // must have same sign
							|| (qtyOnLineMA.abs().compareTo(movementQty.abs())>0)) { // compare absolute values
							// More then line qty on attribute tab for line 10
							m_processMsg = "@Over_Qty_On_Attribute_Tab@ " + sLine.getLine();
							return DOCSTATUS_Invalid;
						}
						
						checkMaterialPolicy(sLine,movementQty.subtract(qtyOnLineMA));
					}
	
					log.fine("Material Transaction");
					MTransaction mtrx = null;
					
					if (!isReversal()) 
					{
						if (oLine != null) 
						{
							BigDecimal toDelivered = oLine.getQtyOrdered()
									.subtract(oLine.getQtyDelivered());
							if (toDelivered.signum() < 0) // IDEMPIERE-2889
								toDelivered = Env.ZERO;
						}
					} 
					
					BigDecimal storageReservationToUpdate = sLine.getMovementQty();
					if (oLine != null)
					{
						if (!isReversal()) 
						{
							if (storageReservationToUpdate.compareTo(oLine.getQtyReserved()) > 0) 
								storageReservationToUpdate = oLine.getQtyReserved();
						}
						else
						{
							BigDecimal tmp = storageReservationToUpdate.negate().add(oLine.getQtyReserved());
							if (tmp.compareTo(oLine.getQtyOrdered()) > 0)
								storageReservationToUpdate = oLine.getQtyOrdered().subtract(oLine.getQtyReserved());
						}
					}
					
					//
					if (sLine.getM_AttributeSetInstance_ID() == 0)
					{
						MInOutLineMA mas[] = MInOutLineMA.get(getCtx(),
							sLine.getM_InOutLine_ID(), get_TrxName());
						for (int j = 0; j < mas.length; j++)
						{
							MInOutLineMA ma = mas[j];
							BigDecimal QtyMA = ma.getMovementQty();
							if (MovementType.charAt(1) == '-')	//	C- Customer Shipment - V- Vendor Return
								QtyMA = QtyMA.negate();
	
							if (product != null && QtyMA.signum() < 0 && MovementType.equals(MOVEMENTTYPE_CustomerShipment) && ma.getM_AttributeSetInstance_ID() > 0
								&& oLine != null && oLine.getM_AttributeSetInstance_ID()==0 && !ma.isAutoGenerated() && !isReversal()) 
							{
								String status = moveOnHandToShipmentASI(product, sLine.getM_Locator_ID(), ma.getM_AttributeSetInstance_ID(), QtyMA.negate(), ma.getDateMaterialPolicy(), 
										sLine.get_ID(), false, get_TrxName());
								if (status != null)
									return status;
							}
							
							//	Update Storage - see also VMatch.createMatchRecord
							if (!MStorageOnHand.add(getCtx(),
								sLine.getM_Locator_ID(),
								sLine.getM_Product_ID(),
								ma.getM_AttributeSetInstance_ID(),
								QtyMA,ma.getDateMaterialPolicy(),
								get_TrxName()))
							{
								String lastError = CLogger.retrieveErrorString("");
								m_processMsg = "Cannot correct Inventory OnHand (MA) [" + product.getValue() + "] - " + lastError;
								return DocAction.STATUS_Invalid;
							}					
							
							//	Create Transaction
							mtrx = new MTransaction (getCtx(), sLine.getAD_Org_ID(),
								MovementType, sLine.getM_Locator_ID(),
								sLine.getM_Product_ID(), ma.getM_AttributeSetInstance_ID(),
								QtyMA, getMovementDate(), get_TrxName());
							mtrx.setM_InOutLine_ID(sLine.getM_InOutLine_ID());
							if (!mtrx.save())
							{
								m_processMsg = "Could not create Material Transaction (MA) [" + product.getValue() + "]";
								return DocAction.STATUS_Invalid;
							}
							
							if (product != null && QtyMA.signum() > 0 && MovementType.equals(MOVEMENTTYPE_CustomerShipment) && ma.getM_AttributeSetInstance_ID() > 0
									&& oLine != null && oLine.getM_AttributeSetInstance_ID()==0 && !ma.isAutoGenerated() && isReversal()) 
							{
								String status = moveOnHandToShipmentASI(product, sLine.getM_Locator_ID(), ma.getM_AttributeSetInstance_ID(), QtyMA.negate(), ma.getDateMaterialPolicy(), 
										sLine.get_ID(), true, get_TrxName());
								if (status != null)
									return status;
							}
						}
						
						if (oLine!=null && mtrx!=null && 
						   ((!isReversal() && oLine.getQtyReserved().signum() > 0) || (isReversal() && oLine.getQtyOrdered().signum() > 0)))
						{					
							if (sLine.getC_OrderLine_ID() != 0 && oLine.getM_Product_ID() > 0)
							{
								IReservationTracer tracer = null;
								IReservationTracerFactory factory = Core.getReservationTracerFactory();
								if (factory != null) {
									tracer = factory.newTracer(getC_DocType_ID(), getDocumentNo(), sLine.getLine(), 
											sLine.get_Table_ID(), sLine.get_ID(), oLine.getM_Warehouse_ID(), 
											oLine.getM_Product_ID(), oLine.getM_AttributeSetInstance_ID(), isSOTrx(), 
											get_TrxName());
								}
								if (!MStorageReservation.add(getCtx(), oLine.getM_Warehouse_ID(),
										oLine.getM_Product_ID(),
										oLine.getM_AttributeSetInstance_ID(),
										storageReservationToUpdate.negate(),
										isSOTrx(),
										get_TrxName(), tracer))
								{
									String lastError = CLogger.retrieveErrorString("");
									m_processMsg = "Cannot correct Inventory " + (isSOTrx()? "Reserved" : "Ordered") + " (MA) - [" + product.getValue() + "] - " + lastError;
									return DocAction.STATUS_Invalid;
								}
							}
						}
						
					}

					if (mtrx == null)
					{
						if (product != null  && MovementType.equals(MOVEMENTTYPE_CustomerShipment) && sLine.getM_AttributeSetInstance_ID() > 0 && Qty.signum() < 0
							&& oLine != null && oLine.getM_AttributeSetInstance_ID()==0 && !isReversal()) 
						{
							String status = moveOnHandToShipmentASI(product, sLine.getM_Locator_ID(), sLine.getM_AttributeSetInstance_ID(), Qty.negate(), null, sLine.get_ID(), false, get_TrxName());
							if (status != null)
								return status;
						}
						
						Timestamp dateMPolicy= null;
						BigDecimal pendingQty = Qty;
						if (pendingQty.signum() < 0) {  // taking from inventory
							MStorageOnHand[] storages = MStorageOnHand.getWarehouse(getCtx(), 0,
									sLine.getM_Product_ID(), sLine.getM_AttributeSetInstance_ID(), null,
									MClient.MMPOLICY_FiFo.equals(product.getMMPolicy()), false,
									sLine.getM_Locator_ID(), get_TrxName());
							for (MStorageOnHand storage : storages) {
								if (pendingQty.signum() == 0)
									break;
								if (storage.getQtyOnHand().compareTo(pendingQty.negate()) >= 0) {
									dateMPolicy = storage.getDateMaterialPolicy();
									break;
								} else if (storage.getQtyOnHand().signum() > 0) {
									BigDecimal onHand = storage.getQtyOnHand();
									// this locator has less qty than required, ship all qtyonhand and iterate to next locator
									if (!MStorageOnHand.add(getCtx(), 
											sLine.getM_Locator_ID(),
											sLine.getM_Product_ID(),
											sLine.getM_AttributeSetInstance_ID(),
											onHand.negate(),storage.getDateMaterialPolicy(),get_TrxName()))
									{
										String lastError = CLogger.retrieveErrorString("");
										m_processMsg = "Cannot correct Inventory OnHand [" + product.getValue() + "] - " + lastError;
										return DocAction.STATUS_Invalid;
									}
									pendingQty = pendingQty.add(onHand);
								}
							}

							if (dateMPolicy == null && storages.length > 0)
								dateMPolicy = storages[0].getDateMaterialPolicy();
						}
	
						if (dateMPolicy == null && product.getM_AttributeSet_ID() > 0) {
							MAttributeSet as = MAttributeSet.get(getCtx(), product.getM_AttributeSet_ID());
							if (as.isUseGuaranteeDateForMPolicy()) {
								MAttributeSetInstance asi = new MAttributeSetInstance(getCtx(), sLine.getM_AttributeSetInstance_ID(), get_TrxName());
								if (asi != null && asi.getGuaranteeDate() != null) {
									dateMPolicy = asi.getGuaranteeDate();
								}
							}
						}

						if (dateMPolicy == null)
							dateMPolicy = getMovementDate();

						//	Fallback: Update Storage - see also VMatch.createMatchRecord
						if (pendingQty.signum() != 0 &&
							!MStorageOnHand.add(getCtx(), 
							sLine.getM_Locator_ID(),
							sLine.getM_Product_ID(),
							sLine.getM_AttributeSetInstance_ID(),
							pendingQty,dateMPolicy,get_TrxName()))
						{
							String lastError = CLogger.retrieveErrorString("");
							m_processMsg = "Cannot correct Inventory OnHand [" + product.getValue() + "] - " + lastError;
							return DocAction.STATUS_Invalid;
						}
						if (oLine!=null && oLine.getM_Product_ID() > 0 &&
							((!isReversal() && oLine.getQtyReserved().signum() > 0) || (isReversal() && oLine.getQtyOrdered().signum() > 0)))  
						{
							IReservationTracer tracer = null;
							IReservationTracerFactory factory = Core.getReservationTracerFactory();
							if (factory != null) {
								tracer = factory.newTracer(getC_DocType_ID(), getDocumentNo(), sLine.getLine(), 
										sLine.get_Table_ID(), sLine.get_ID(), oLine.getM_Warehouse_ID(), 
										oLine.getM_Product_ID(), oLine.getM_AttributeSetInstance_ID(), isSOTrx(), 
										get_TrxName());
							}
							if (!MStorageReservation.add(getCtx(), oLine.getM_Warehouse_ID(),
									oLine.getM_Product_ID(),
									oLine.getM_AttributeSetInstance_ID(),
									storageReservationToUpdate.negate(), isSOTrx(), get_TrxName(), tracer))
							{
								m_processMsg = "Cannot correct Inventory Reserved " + (isSOTrx()? "Reserved [" :"Ordered [") + product.getValue() + "]";
								return DocAction.STATUS_Invalid;
							}
						}
						
						//	FallBack: Create Transaction
						mtrx = new MTransaction (getCtx(), sLine.getAD_Org_ID(),
							MovementType, sLine.getM_Locator_ID(),
							sLine.getM_Product_ID(), sLine.getM_AttributeSetInstance_ID(),
							Qty, getMovementDate(), get_TrxName());
						mtrx.setM_InOutLine_ID(sLine.getM_InOutLine_ID());
						if (!mtrx.save())
						{
							m_processMsg = CLogger.retrieveErrorString("Could not create Material Transaction [" + product.getValue() + "]");
							return DocAction.STATUS_Invalid;
						}
						
						if (product != null  && MovementType.equals(MOVEMENTTYPE_CustomerShipment) && sLine.getM_AttributeSetInstance_ID() > 0 && Qty.signum() > 0
							&& oLine != null && oLine.getM_AttributeSetInstance_ID()==0 && isReversal()) 
						{
							String status = moveOnHandToShipmentASI(product, sLine.getM_Locator_ID(), sLine.getM_AttributeSetInstance_ID(), Qty.negate(), getMovementDate(), sLine.get_ID(), true, get_TrxName());
							if (status != null)
								return status;
						}
					}
				}	//	stock movement
	
				//	Correct Order Line
				if (product != null && oLine != null)		//	other in VMatch.createMatchRecord
				{
					if (oLine.getQtyOrdered().signum() >= 0)
					{
						oLine.setQtyReserved(oLine.getQtyReserved().subtract(sLine.getMovementQty()));

						if (oLine.getQtyReserved().signum() == -1)
							oLine.setQtyReserved(Env.ZERO);
						else if (oLine.getQtyDelivered().compareTo(oLine.getQtyOrdered()) > 0)
							oLine.setQtyReserved(Env.ZERO);
					}
				}
	
				//	Update Sales Order Line
				if (oLine != null)
				{
					if (isSOTrx()							//	PO is done by Matching
						|| sLine.getM_Product_ID() == 0)	//	PO Charges, empty lines
					{
						if (isSOTrx())
							oLine.setQtyDelivered(oLine.getQtyDelivered().subtract(Qty));
						else
							oLine.setQtyDelivered(oLine.getQtyDelivered().add(Qty));
						oLine.setDateDelivered(getMovementDate());	//	overwrite=last
					}
					if (!oLine.save())
					{
						m_processMsg = "Could not update Order Line";
						return DocAction.STATUS_Invalid;
					}
					else
						if (log.isLoggable(Level.FINE)) log.fine("OrderLine -> Reserved=" + oLine.getQtyReserved()
							+ ", Delivered=" + oLine.getQtyReserved());
				}
	            //  Update RMA Line Qty Delivered
	            else if (rmaLine != null)
	            {
	                if (isSOTrx())
	                {
	                    rmaLine.setQtyDelivered(rmaLine.getQtyDelivered().add(Qty));
	                }
	                else
	                {
	                    rmaLine.setQtyDelivered(rmaLine.getQtyDelivered().subtract(Qty));
	                }
	                if (!rmaLine.save())
	                {
	                    m_processMsg = "Could not update RMA Line";
	                    return DocAction.STATUS_Invalid;
	                }
	            }
	
				//	Create Asset for SO
				if (product != null
					&& isSOTrx()
					&& product.isCreateAsset()
					&& !product.getM_Product_Category().getA_Asset_Group().isFixedAsset()
					&& sLine.getMovementQty().signum() > 0
					&& !isReversal())
				{
					log.fine("Asset");
					info.append("@A_Asset_ID@: ");
					int noAssets = sLine.getMovementQty().intValue();
					if (!product.isOneAssetPerUOM())
						noAssets = 1;
					for (int i = 0; i < noAssets; i++)
					{
						if (i > 0)
							info.append(" - ");
						int deliveryCount = i+1;
						if (!product.isOneAssetPerUOM())
							deliveryCount = 0;
						MAsset asset = new MAsset (this, sLine, deliveryCount);
						if (!asset.save(get_TrxName()))
						{
							m_processMsg = "Could not create Asset";
							return DocAction.STATUS_Invalid;
						}
						info.append(asset.getValue());
					}
				}	//	Asset
	
	
				//	Matching
				if (!isSOTrx()
					&& sLine.getM_Product_ID() != 0
					&& !isReversal())
				{
					BigDecimal matchQty = sLine.getMovementQty();
					//	Invoice - Receipt Match (requires Product)
					MInvoiceLine iLine = MInvoiceLine.getOfInOutLine (sLine);
					if (iLine != null && iLine.getM_Product_ID() != 0)
					{
						if (matchQty.compareTo(iLine.getQtyInvoiced())>0)
							matchQty = iLine.getQtyInvoiced();
	
						MMatchInv[] matches = MMatchInv.get(getCtx(),
							sLine.getM_InOutLine_ID(), iLine.getC_InvoiceLine_ID(), get_TrxName());
						if (matches == null || matches.length == 0)
						{
							MMatchInv inv = new MMatchInv (iLine, getMovementDate(), matchQty);
							if (sLine.getM_AttributeSetInstance_ID() != iLine.getM_AttributeSetInstance_ID())
							{
								iLine.setM_AttributeSetInstance_ID(sLine.getM_AttributeSetInstance_ID());
								iLine.saveEx();	//	update matched invoice with ASI
								inv.setM_AttributeSetInstance_ID(sLine.getM_AttributeSetInstance_ID());
							}
							if (!inv.save(get_TrxName()))
							{
								m_processMsg = CLogger.retrieveErrorString("Could not create Inv Matching");
								return DocAction.STATUS_Invalid;
							}
							addDocsPostProcess(inv);
						}
					}
	
					//	Link to Order
					if (sLine.getC_OrderLine_ID() != 0)
					{
						log.fine("PO Matching");
						//	Ship - PO
						MMatchPO po = MMatchPO.create (null, sLine, getMovementDate(), matchQty);
						if (po != null) {
							if (!po.save(get_TrxName()))
							{
								m_processMsg = "Could not create PO Matching";
								return DocAction.STATUS_Invalid;
							}
							if (!po.isPosted())
								addDocsPostProcess(po);
							
							MMatchInv[] matchInvList = MMatchInv.getInOut(getCtx(), getM_InOut_ID(), get_TrxName());
							for (MMatchInv matchInvCreated : matchInvList)
								addDocsPostProcess(matchInvCreated);
						}
						//	Update PO with ASI
						if (   oLine != null && oLine.getM_AttributeSetInstance_ID() == 0
							&& sLine.getMovementQty().compareTo(oLine.getQtyOrdered()) == 0) //  just if full match [ 1876965 ]
						{
							oLine.setM_AttributeSetInstance_ID(sLine.getM_AttributeSetInstance_ID());
							oLine.saveEx(get_TrxName());
						}
					}
					else	//	No Order - Try finding links via Invoice
					{
						//	Invoice has an Order Link
						if (iLine != null && iLine.getC_OrderLine_ID() != 0)
						{
							//	Invoice is created before  Shipment
							log.fine("PO(Inv) Matching");
							//	Ship - Invoice
							MMatchPO po = MMatchPO.create (iLine, sLine,
								getMovementDate(), matchQty);
							if (po != null) {
								if (!po.save(get_TrxName()))
								{
									m_processMsg = "Could not create PO(Inv) Matching";
									return DocAction.STATUS_Invalid;
								}
								if (!po.isPosted())
									addDocsPostProcess(po);
							}
							
							//	Update PO with ASI
							oLine = new MOrderLine (getCtx(), iLine.getC_OrderLine_ID(), get_TrxName());
							if (   oLine != null && oLine.getM_AttributeSetInstance_ID() == 0
								&& sLine.getMovementQty().compareTo(oLine.getQtyOrdered()) == 0) //  just if full match [ 1876965 ]
							{
								oLine.setM_AttributeSetInstance_ID(sLine.getM_AttributeSetInstance_ID());
								oLine.saveEx(get_TrxName());
							}
						}
					}	//	No Order
				}	//	PO Matching
			}
			catch (NegativeInventoryDisallowedException e)
			{
				log.severe(e.getMessage());
				errors.append(Msg.getElement(getCtx(), "Line")).append(" ").append(sLine.getLine()).append(": ");
				errors.append(e.getMessage()).append("\n");
			}
		}	//	for all lines

		if (errors.toString().length() > 0)
		{
			m_processMsg = errors.toString();
			return DocAction.STATUS_Invalid;
		}
		
		//	Counter Documents
		MInOut counter = createCounterDoc();
		if (counter != null)
			info.append(" - @CounterDoc@: @M_InOut_ID@=").append(counter.getDocumentNo());

		//  Drop Shipments
		MInOut dropShipment = createDropShipment();
		if (dropShipment != null)
		{
			info.append(" - @DropShipment@: @M_InOut_ID@=").append(dropShipment.getDocumentNo());
			ProcessInfo pi = MWFActivity.getCurrentWorkflowProcessInfo();
			if (pi != null)
			{
				Trx.get(get_TrxName(), false).addTrxEventListener(new TrxEventListener() {					
					@Override
					public void afterRollback(Trx trx, boolean success) {
						trx.removeTrxEventListener(this);
					}
					
					@Override
					public void afterCommit(Trx trx, boolean success) {
						if (success)
							pi.addLog(pi.getAD_PInstance_ID(), null, null, dropShipment.getDocumentInfo(), Table_ID, dropShipment.get_ID());
						trx.removeTrxEventListener(this);
					}
					
					@Override
					public void afterClose(Trx trx) {
					}
				});
			}
		}
		if (dropShipment != null)
			addDocsPostProcess(dropShipment);
		//	User Validation
		String valid = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_COMPLETE);
		if (valid != null)
		{
			m_processMsg = valid;
			return DocAction.STATUS_Invalid;
		}

		m_processMsg = info.toString();
		setProcessed(true);
		setDocAction(DOCACTION_Close);
		return DocAction.STATUS_Completed;
	}	//	completeIt

	/**
	 * Outstanding (not processed) Customer Confirmations ?
	 * @return true if there are pending Customer Confirmations
	 */
	public boolean pendingCustomerConfirmations() {
		MInOutConfirm[] confirmations = getConfirmations(true);
		for (int i = 0; i < confirmations.length; i++) {
			MInOutConfirm confirm = confirmations[i];
			if (!confirm.isProcessed()) {
				if (MInOutConfirm.CONFIRMTYPE_CustomerConfirmation.equals(confirm.getConfirmType())) {
					continue;
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Outstanding (not processed) Confirmations ?
	 * @return true if there are pending Confirmations
	 */
	public boolean pendingConfirmations() {
		MInOutConfirm[] confirmations = getConfirmations(true);
		for (int i = 0; i < confirmations.length; i++) {
			MInOutConfirm confirm = confirmations[i];
			if (!confirm.isProcessed()) {
				return true;
			}
		}
		return false;
	}

	/* Save array of documents to process AFTER completing this one */
	ArrayList<PO> docsPostProcess = new ArrayList<PO>();

	protected void addDocsPostProcess(PO doc) {
		docsPostProcess.add(doc);
	}

	@Override
	public List<PO> getDocsPostProcess() {
		return docsPostProcess;
	}

	/**
	 * Automatically creates a customer shipment for any
	 * drop shipment material receipt
	 * Based on createCounterDoc() by JJ
	 * @return shipment if created else null
	 */
	protected MInOut createDropShipment() {

		if ( isSOTrx() || !isDropShip() || getC_Order_ID() == 0 )
			return null;

		int linkedOrderID = new MOrder (getCtx(), getC_Order_ID(), get_TrxName()).getLink_Order_ID();
		if (linkedOrderID <= 0)
			return null;

		//	Document Type
		int C_DocTypeTarget_ID = 0;
		MDocType[] shipmentTypes = MDocType.getOfDocBaseType(getCtx(), MDocType.DOCBASETYPE_MaterialDelivery);

		for (int i = 0; i < shipmentTypes.length; i++ )
		{
			if (shipmentTypes[i].isSOTrx() && ( C_DocTypeTarget_ID == 0 || shipmentTypes[i].isDefault() ) )
				C_DocTypeTarget_ID = shipmentTypes[i].getC_DocType_ID();
		}

		//	Deep Copy
		MInOut dropShipment = copyFrom(this, getMovementDate(), getDateAcct(),
			C_DocTypeTarget_ID, !isSOTrx(), false, get_TrxName(), true);

		dropShipment.setC_Order_ID(linkedOrderID);

		// get invoice id from linked order
		int invID = new MOrder (getCtx(), linkedOrderID, get_TrxName()).getC_Invoice_ID();
		if ( invID != 0 )
			dropShipment.setC_Invoice_ID(invID);

		dropShipment.setC_BPartner_ID(getDropShip_BPartner_ID());
		dropShipment.setC_BPartner_Location_ID(getDropShip_Location_ID());
		dropShipment.setAD_User_ID(getDropShip_User_ID());
		dropShipment.setIsDropShip(false);
		dropShipment.setDropShip_BPartner_ID(0);
		dropShipment.setDropShip_Location_ID(0);
		dropShipment.setDropShip_User_ID(0);
		dropShipment.setMovementType(MOVEMENTTYPE_CustomerShipment);
		if (!Util.isEmpty(getTrackingNo()) && getM_Shipper_ID() > 0 && 
				DELIVERYVIARULE_Shipper.equals(getDeliveryViaRule()))
		{
			dropShipment.setTrackingNo(getTrackingNo());
			dropShipment.setDeliveryViaRule(DELIVERYVIARULE_Shipper);
			dropShipment.setM_Shipper_ID(getM_Shipper_ID());
		}
		
		//	References (Should not be required
		dropShipment.setSalesRep_ID(getSalesRep_ID());
		dropShipment.saveEx(get_TrxName());

		//		Update line order references to linked sales order lines
		MInOutLine[] lines = dropShipment.getLines(true);
		for (int i = 0; i < lines.length; i++)
		{
			MInOutLine dropLine = lines[i];
			MOrderLine ol = new MOrderLine(getCtx(), dropLine.getC_OrderLine_ID(), null);
			if ( ol.getC_OrderLine_ID() != 0 ) {
				dropLine.setC_OrderLine_ID(ol.getLink_OrderLine_ID());
				dropLine.saveEx();
			}
		}

		if (log.isLoggable(Level.FINE)) log.fine(dropShipment.toString());

		// do not post immediate dropshipment, should post after source shipment
		dropShipment.set_Attribute(DocumentEngine.DOCUMENT_POST_IMMEDIATE_AFTER_COMPLETE, Boolean.FALSE);
		ProcessInfo processInfo = MWorkflow.runDocumentActionWorkflow(dropShipment, DocAction.ACTION_Complete);
		if (processInfo.isError())
			throw new RuntimeException(Msg.getMsg(getCtx(), "FailedProcessingDocument") + ": " + dropShipment.toString() + " - " + dropShipment.getProcessMsg());
		dropShipment.saveEx();

		return dropShipment;
	}

	/**
	 * 	Set the definite document number after completed
	 */
	protected void setDefiniteDocumentNo() {
		MDocType dt = MDocType.get(getCtx(), getC_DocType_ID());
		if (dt.isOverwriteDateOnComplete()) {
			setMovementDate(TimeUtil.getDay(0));
			if (getDateAcct().before(getMovementDate())) {
				setDateAcct(getMovementDate());
				MPeriod.testPeriodOpen(getCtx(), getDateAcct(), getC_DocType_ID(), getAD_Org_ID());
			}
		}
		if (dt.isOverwriteSeqOnComplete()) {
			String value = DB.getDocumentNo(getC_DocType_ID(), get_TrxName(), true, this);
			if (value != null)
				setDocumentNo(value);
		}
	}

	/**
	 * 	Check Material Policy
	 * 	Sets line ASI
	 */
	protected void checkMaterialPolicy(MInOutLine line,BigDecimal qty)
	{
			
		int no = MInOutLineMA.deleteInOutLineMA(line.getM_InOutLine_ID(), get_TrxName());
		if (no > 0)
			if (log.isLoggable(Level.CONFIG)) log.config("Delete old #" + no);
		
		if(Env.ZERO.compareTo(qty)==0)
			return;
		
		//	Incoming Trx
		String MovementType = getMovementType();
		boolean inTrx = MovementType.charAt(1) == '+';	//	V+ Vendor Receipt

		boolean needSave = false;

		MProduct product = line.getProduct();

		//	Need to have Location
		if (product != null
				&& line.getM_Locator_ID() == 0)
		{
			line.setM_Warehouse_ID(getM_Warehouse_ID());
			line.setM_Locator_ID(inTrx ? Env.ZERO : line.getMovementQty());	//	default Locator
			needSave = true;
		}

		//	Attribute Set Instance
		//  Create an  Attribute Set Instance to any receipt FIFO/LIFO
		if (product != null && line.getM_AttributeSetInstance_ID() == 0)
		{
			//Validate Transaction
			if (getMovementType().compareTo(MInOut.MOVEMENTTYPE_VendorReceipts) == 0 )
			{
				//auto balance negative on hand
				BigDecimal qtyToReceive = autoBalanceNegative(line, product,qty);
				
				//Allocate remaining qty.
				if (qtyToReceive.compareTo(Env.ZERO)>0)
				{
					MInOutLineMA ma = MInOutLineMA.addOrCreate(line, 0, qtyToReceive, getMovementDate(),true); 
					ma.saveEx();
				}
				
			} else if (getMovementType().compareTo(MInOut.MOVEMENTTYPE_CustomerReturns) == 0){
				BigDecimal qtyToReturn = autoBalanceNegative(line, product,qty);
				
				if (line.getM_RMALine_ID()!=0 && qtyToReturn.compareTo(Env.ZERO)>0){
					//Linking to shipment line
					MRMALine rmaLine = new MRMALine(getCtx(), line.getM_RMALine_ID(), get_TrxName());
					if(rmaLine.getM_InOutLine_ID()>0){
						//retrieving ASI which is not already returned
						MInOutLineMA shipmentMAS[] = MInOutLineMA.getNonReturned(getCtx(), rmaLine.getM_InOutLine_ID(), get_TrxName());
						
						for(MInOutLineMA sMA : shipmentMAS){
							BigDecimal lineMAQty = sMA.getMovementQty();
							if(lineMAQty.compareTo(qtyToReturn)>0){
								lineMAQty = qtyToReturn;
							}
							
							MInOutLineMA ma = MInOutLineMA.addOrCreate(line, sMA.getM_AttributeSetInstance_ID(), lineMAQty, sMA.getDateMaterialPolicy(),true); 
							ma.saveEx();			
							
							qtyToReturn = qtyToReturn.subtract(lineMAQty);
							if(qtyToReturn.compareTo(Env.ZERO)==0)
								break;
						}
					}
				}
				if(qtyToReturn.compareTo(Env.ZERO)>0){
					//Use movement data for  Material policy if no linkage found to Shipment.
					MInOutLineMA ma = MInOutLineMA.addOrCreate(line, 0, qtyToReturn, getMovementDate(),true); 
					ma.saveEx();			
				}	
			}
			// Create consume the Attribute Set Instance using policy FIFO/LIFO
			else if(getMovementType().compareTo(MInOut.MOVEMENTTYPE_VendorReturns) == 0 || getMovementType().compareTo(MInOut.MOVEMENTTYPE_CustomerShipment) == 0)
			{
				String MMPolicy = product.getMMPolicy();
				Timestamp minGuaranteeDate = getMovementDate();
				MStorageOnHand[] storages = MStorageOnHand.getWarehouse(getCtx(), getM_Warehouse_ID(), line.getM_Product_ID(), line.getM_AttributeSetInstance_ID(),
						minGuaranteeDate, MClient.MMPOLICY_FiFo.equals(MMPolicy), true, line.getM_Locator_ID(), get_TrxName(), false);
				BigDecimal qtyToDeliver = qty;
				for (MStorageOnHand storage: storages)
				{
					if (storage.getQtyOnHand().compareTo(qtyToDeliver) >= 0)
					{
						MInOutLineMA ma = new MInOutLineMA (line,
								storage.getM_AttributeSetInstance_ID(),
								qtyToDeliver,storage.getDateMaterialPolicy(),true);
						ma.saveEx();
						qtyToDeliver = Env.ZERO;
					}
					else
					{
						MInOutLineMA ma = new MInOutLineMA (line,
								storage.getM_AttributeSetInstance_ID(),
								storage.getQtyOnHand(),storage.getDateMaterialPolicy(),true);
						ma.saveEx();
						qtyToDeliver = qtyToDeliver.subtract(storage.getQtyOnHand());
						if (log.isLoggable(Level.FINE)) log.fine( ma + ", QtyToDeliver=" + qtyToDeliver);
					}

					if (qtyToDeliver.signum() == 0)
						break;
				}

				if (qtyToDeliver.signum() != 0)
				{					
					//Over Delivery
					MInOutLineMA ma = MInOutLineMA.addOrCreate(line, line.getM_AttributeSetInstance_ID(), qtyToDeliver, getMovementDate(),true);
					ma.saveEx();
					if (log.isLoggable(Level.FINE)) log.fine("##: " + ma);
				}
			}	//	outgoing Trx
		}	//	attributeSetInstance

		if (needSave)
		{
			line.saveEx();
		}
	}	//	checkMaterialPolicy

	protected BigDecimal autoBalanceNegative(MInOutLine line, MProduct product,BigDecimal qtyToReceive) {
		MStorageOnHand[] storages = MStorageOnHand.getWarehouseNegative(getCtx(), getM_Warehouse_ID(), line.getM_Product_ID(), 0,
				null, MClient.MMPOLICY_FiFo.equals(product.getMMPolicy()), line.getM_Locator_ID(), get_TrxName(), false);
		
		Timestamp dateMPolicy = null;
			
		for (MStorageOnHand storage : storages)
		{
			if (storage.getQtyOnHand().signum() < 0 && qtyToReceive.compareTo(Env.ZERO)>0)
			{
				dateMPolicy = storage.getDateMaterialPolicy();
				BigDecimal lineMAQty = qtyToReceive;
				if(lineMAQty.compareTo(storage.getQtyOnHand().negate())>0)
					lineMAQty = storage.getQtyOnHand().negate();
				
				//Using ASI from storage record
				MInOutLineMA ma = new MInOutLineMA (line, storage.getM_AttributeSetInstance_ID(), lineMAQty,dateMPolicy,true);
				ma.saveEx();			
				qtyToReceive = qtyToReceive.subtract(lineMAQty);
			}
		}
		return qtyToReceive;
	}


	/**************************************************************************
	 * 	Create Counter Document
	 * 	@return InOut
	 */
	protected MInOut createCounterDoc()
	{
		//	Is this a counter doc ?
		if (getRef_InOut_ID() != 0)
			return null;

		//	Org Must be linked to BPartner
		MOrg org = MOrg.get(getCtx(), getAD_Org_ID());
		int counterC_BPartner_ID = org.getLinkedC_BPartner_ID(get_TrxName());
		if (counterC_BPartner_ID == 0)
			return null;
		//	Business Partner needs to be linked to Org
		MBPartner bp = new MBPartner (getCtx(), getC_BPartner_ID(), get_TrxName());
		int counterAD_Org_ID = bp.getAD_OrgBP_ID();
		if (counterAD_Org_ID == 0)
			return null;

		MBPartner counterBP = new MBPartner (getCtx(), counterC_BPartner_ID, null);
		MOrgInfo counterOrgInfo = MOrgInfo.get(getCtx(), counterAD_Org_ID, get_TrxName());
		if (log.isLoggable(Level.INFO)) log.info("Counter BP=" + counterBP.getName());

		//	Document Type
		int C_DocTypeTarget_ID = 0;
		MDocTypeCounter counterDT = MDocTypeCounter.getCounterDocType(getCtx(), getC_DocType_ID());
		if (counterDT != null)
		{
			if (log.isLoggable(Level.FINE)) log.fine(counterDT.toString());
			if (!counterDT.isCreateCounter() || !counterDT.isValid())
				return null;
			C_DocTypeTarget_ID = counterDT.getCounter_C_DocType_ID();
		}
		else	//	indirect
		{
			C_DocTypeTarget_ID = MDocTypeCounter.getCounterDocType_ID(getCtx(), getC_DocType_ID());
			if (log.isLoggable(Level.FINE)) log.fine("Indirect C_DocTypeTarget_ID=" + C_DocTypeTarget_ID);
			if (C_DocTypeTarget_ID <= 0)
				return null;
		}

		//	Deep Copy
		MInOut counter = copyFrom(this, getMovementDate(), getDateAcct(),
			C_DocTypeTarget_ID, !isSOTrx(), true, get_TrxName(), true);

		//
		counter.setAD_Org_ID(counterAD_Org_ID);
		counter.setM_Warehouse_ID(counterOrgInfo.getM_Warehouse_ID());
		//
		counter.setBPartner(counterBP);

		if ( isDropShip() )
		{
			counter.setIsDropShip(true );
			counter.setDropShip_BPartner_ID(getDropShip_BPartner_ID());
			counter.setDropShip_Location_ID(getDropShip_Location_ID());
			counter.setDropShip_User_ID(getDropShip_User_ID());
		}

		//	Refernces (Should not be required
		counter.setSalesRep_ID(getSalesRep_ID());
		counter.saveEx(get_TrxName());

		String MovementType = counter.getMovementType();
		boolean inTrx = MovementType.charAt(1) == '+';	//	V+ Vendor Receipt

		//	Update copied lines
		MInOutLine[] counterLines = counter.getLines(true);
		for (int i = 0; i < counterLines.length; i++)
		{
			MInOutLine counterLine = counterLines[i];
			counterLine.setClientOrg(counter);
			counterLine.setM_Warehouse_ID(counter.getM_Warehouse_ID());
			counterLine.setM_Locator_ID(0);
			counterLine.setM_Locator_ID(inTrx ? Env.ZERO : counterLine.getMovementQty());
			//
			counterLine.saveEx(get_TrxName());
		}

		if (log.isLoggable(Level.FINE)) log.fine(counter.toString());

		//	Document Action
		if (counterDT != null)
		{
			if (counterDT.getDocAction() != null)
			{
				counter.setDocAction(counterDT.getDocAction());
				// added AdempiereException by zuhri
				if (!counter.processIt(counterDT.getDocAction()))
					throw new AdempiereException(Msg.getMsg(getCtx(), "FailedProcessingDocument") + " - " + counter.getProcessMsg());
				// end added
				counter.saveEx(get_TrxName());
			}
		}
		return counter;
	}	//	createCounterDoc

	/**
	 * 	Void Document.
	 * 	@return true if success
	 */
	public boolean voidIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());		

		if (DOCSTATUS_Closed.equals(getDocStatus())
			|| DOCSTATUS_Reversed.equals(getDocStatus())
			|| DOCSTATUS_Voided.equals(getDocStatus()))
		{
			m_processMsg = "Document Closed: " + getDocStatus();
			return false;
		}

		//	Not Processed
		if (DOCSTATUS_Drafted.equals(getDocStatus())
			|| DOCSTATUS_Invalid.equals(getDocStatus())
			|| DOCSTATUS_InProgress.equals(getDocStatus())
			|| DOCSTATUS_Approved.equals(getDocStatus())
			|| DOCSTATUS_NotApproved.equals(getDocStatus()) )
		{
			// Before Void
			m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_VOID);
			if (m_processMsg != null)
				return false;
			
			//	Set lines to 0
			MInOutLine[] lines = getLines(false);
			for (int i = 0; i < lines.length; i++)
			{
				MInOutLine line = lines[i];
				BigDecimal old = line.getMovementQty();
				if (old.signum() != 0)
				{
					line.setQty(Env.ZERO);
					StringBuilder msgadd = new StringBuilder("Void (").append(old).append(")");
					line.addDescription(msgadd.toString());
					line.saveEx(get_TrxName());
				}
			}
			//
			// Void Confirmations
			setDocStatus(DOCSTATUS_Voided); // need to set & save docstatus to be able to check it in MInOutConfirm.voidIt()
			saveEx();
			voidConfirmations();
		}
		else
		{
			boolean accrual = false;
			try 
			{
				MPeriod.testPeriodOpen(getCtx(), getDateAcct(), getC_DocType_ID(), getAD_Org_ID());
			}
			catch (PeriodClosedException e) 
			{
				accrual = true;
			}
			
			if (accrual)
				return reverseAccrualIt();
			else
				return reverseCorrectIt();
		}

		// After Void
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_VOID);
		if (m_processMsg != null)
			return false;

		setProcessed(true);
		setDocAction(DOCACTION_None);
		return true;
	}	//	voidIt

	/**
	 * 	Close Document.
	 * 	@return true if success
	 */
	public boolean closeIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		// Before Close
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_CLOSE);
		if (m_processMsg != null)
			return false;

		setProcessed(true);
		setDocAction(DOCACTION_None);

		// After Close
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_CLOSE);
		if (m_processMsg != null)
			return false;
		return true;
	}	//	closeIt

	/**
	 * 	Reverse Correction - same date
	 * 	@return true if success
	 */
	public boolean reverseCorrectIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		// Before reverseCorrect
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_REVERSECORRECT);
		if (m_processMsg != null)
			return false;

		MInOut reversal = reverse(false);
		if (reversal == null)
			return false;

		// After reverseCorrect
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_REVERSECORRECT);
		if (m_processMsg != null)
			return false;

		m_processMsg = reversal.getDocumentNo();
		setProcessed(true);
		setDocStatus(DOCSTATUS_Reversed);		//	 may come from void
		setDocAction(DOCACTION_None);
		return true;
	}	//	reverseCorrectionIt

	protected MInOut reverse(boolean accrual) {
		MDocType dt = MDocType.get(getCtx(), getC_DocType_ID());
		Timestamp reversalDate = accrual ? Env.getContextAsDate(getCtx(), Env.DATE) : getDateAcct();
		if (reversalDate == null) {
			reversalDate = new Timestamp(System.currentTimeMillis());
		}
		Timestamp reversalMovementDate = accrual ? reversalDate : getMovementDate();
		if (!MPeriod.isOpen(getCtx(), reversalDate, dt.getDocBaseType(), getAD_Org_ID()))
		{
			m_processMsg = "@PeriodClosed@";
			return null;
		}

		//	Reverse/Delete Matching
		if (!isSOTrx())
		{
			if (!reverseMatching(reversalDate))
				return null;			
		}

		//	Deep Copy
		MInOut reversal = copyFrom (this, reversalMovementDate, reversalDate,
			getC_DocType_ID(), isSOTrx(), false, get_TrxName(), true);
		if (reversal == null)
		{
			m_processMsg = "Could not create Ship Reversal";
			return null;
		}
		reversal.setReversal(true);

		//	Reverse Line Qty
		MInOutLine[] sLines = getLines(false);
		MInOutLine[] rLines = reversal.getLines(false);
		for (int i = 0; i < rLines.length; i++)
		{
			MInOutLine rLine = rLines[i];
			rLine.setQtyEntered(rLine.getQtyEntered().negate());
			rLine.setMovementQty(rLine.getMovementQty().negate());
			rLine.setM_AttributeSetInstance_ID(sLines[i].getM_AttributeSetInstance_ID());
			// Goodwill: store original (voided/reversed) document line
			rLine.setReversalLine_ID(sLines[i].getM_InOutLine_ID());
			if (!rLine.save(get_TrxName()))
			{
				m_processMsg = "Could not correct Ship Reversal Line";
				return null;
			}
			//	We need to copy MA
			if (rLine.getM_AttributeSetInstance_ID() == 0)
			{
				MInOutLineMA mas[] = MInOutLineMA.get(getCtx(),
					sLines[i].getM_InOutLine_ID(), get_TrxName());
				for (int j = 0; j < mas.length; j++)
				{
					MInOutLineMA ma = new MInOutLineMA (rLine,
						mas[j].getM_AttributeSetInstance_ID(),
						mas[j].getMovementQty().negate(),mas[j].getDateMaterialPolicy(),mas[j].isAutoGenerated());
					ma.saveEx();
				}
			}
			//	De-Activate Asset
			MAsset asset = MAsset.getFromShipment(getCtx(), sLines[i].getM_InOutLine_ID(), get_TrxName());
			if (asset != null)
			{
				asset.setIsActive(false);
				asset.setDescription(asset.getDescription() + " (" + reversal.getDocumentNo() + " #" + rLine.getLine() + "<-)");
				asset.saveEx();
			}
			// Un-Link inoutline to Invoiceline
			String sql = "SELECT C_InvoiceLine_ID FROM C_InvoiceLine WHERE M_InOutLine_ID=?";
			PreparedStatement pstmt = null;
			ResultSet rs = null;
			try
			{
				pstmt = DB.prepareStatement(sql, get_TrxName());
				pstmt.setInt(1, sLines[i].getM_InOutLine_ID());
				rs = pstmt.executeQuery();
				while (rs.next())
				{
					int invoiceLineId = rs.getInt(1);
					if (invoiceLineId > 0 ){
						MInvoiceLine iLine = new MInvoiceLine(getCtx(),invoiceLineId , get_TrxName());
						iLine.setM_InOutLine_ID(0);
						iLine.saveEx();
					}
				}
			}
			catch (SQLException e)
			{
				throw new DBException(e, sql);
			}
			finally
			{
				DB.close(rs, pstmt);
				rs = null; pstmt = null;
			}
		}
		reversal.setC_Order_ID(getC_Order_ID());
		// Set M_RMA_ID
		reversal.setM_RMA_ID(getM_RMA_ID());
		StringBuilder msgadd = new StringBuilder("{->").append(getDocumentNo()).append(")");
		reversal.addDescription(msgadd.toString());
		//FR1948157
		reversal.setReversal_ID(getM_InOut_ID());
		reversal.saveEx(get_TrxName());
		//
		reversal.docsPostProcess = this.docsPostProcess;
		this.docsPostProcess = new ArrayList<PO>();
		//
		if (!reversal.processIt(DocAction.ACTION_Complete)
			|| !reversal.getDocStatus().equals(DocAction.STATUS_Completed))
		{
			m_processMsg = "Reversal ERROR: " + reversal.getProcessMsg();
			return null;
		}
		reversal.closeIt();
		reversal.setProcessing (false);
		reversal.setDocStatus(DOCSTATUS_Reversed);
		reversal.setDocAction(DOCACTION_None);
		reversal.saveEx(get_TrxName());
		//
		msgadd = new StringBuilder("(").append(reversal.getDocumentNo()).append("<-)");
		addDescription(msgadd.toString());
		
		//
		// Void Confirmations
		setDocStatus(DOCSTATUS_Reversed); // need to set & save docstatus to be able to check it in MInOutConfirm.voidIt()
		saveEx();
		//FR1948157
		this.setReversal_ID(reversal.getM_InOut_ID());
		voidConfirmations();
		return reversal;
	}

	protected boolean reverseMatching(Timestamp reversalDate) {
		MMatchInv[] mInv = MMatchInv.getInOut(getCtx(), getM_InOut_ID(), get_TrxName());
		for (MMatchInv mMatchInv : mInv)
		{		
			if (mMatchInv.getReversal_ID() > 0)
				continue;
			
			String description = mMatchInv.getDescription();
			if (description == null || !description.endsWith("<-)"))
			{
				if (!mMatchInv.reverse(reversalDate))
				{
					log.log(Level.SEVERE, "Failed to create reversal for match invoice " + mMatchInv.getDocumentNo());
					return false;
				}
				addDocsPostProcess(new MMatchInv(Env.getCtx(), mMatchInv.getReversal_ID(), get_TrxName()));
			}
		}
		MMatchPO[] mMatchPOList = MMatchPO.getInOut(getCtx(), getM_InOut_ID(), get_TrxName());
		for (MMatchPO mMatchPO : mMatchPOList) 
		{
			if (mMatchPO.getReversal_ID() > 0)
				continue;
			
			String description = mMatchPO.getDescription();
			if (description == null || !description.endsWith("<-)"))
			{
				if (!mMatchPO.reverse(reversalDate))
				{
					log.log(Level.SEVERE, "Failed to create reversal for match purchase order " + mMatchPO.getDocumentNo());
					return false;
				}
				addDocsPostProcess(new MMatchPO(Env.getCtx(), mMatchPO.getReversal_ID(), get_TrxName()));
			}
		}
		return true;
	}

	/**
	 * 	Reverse Accrual - none
	 * 	@return false
	 */
	public boolean reverseAccrualIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		// Before reverseAccrual
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_REVERSEACCRUAL);
		if (m_processMsg != null)
			return false;

		MInOut reversal = reverse(true);
		if (reversal == null)
			return false;
		
		// After reverseAccrual
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_REVERSEACCRUAL);
		if (m_processMsg != null)
			return false;

		m_processMsg = reversal.getDocumentNo();
		setProcessed(true);
		setDocStatus(DOCSTATUS_Reversed);		//	 may come from void
		setDocAction(DOCACTION_None);
		return true;
	}	//	reverseAccrualIt

	/**
	 * 	Re-activate
	 * 	@return false
	 */
	public boolean reActivateIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		// Before reActivate
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_REACTIVATE);
		if (m_processMsg != null)
			return false;

		// After reActivate
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_REACTIVATE);
		if (m_processMsg != null)
			return false;

		return false;
	}	//	reActivateIt


	/*************************************************************************
	 * 	Get Summary
	 *	@return Summary of Document
	 */
	public String getSummary()
	{
		StringBuilder sb = new StringBuilder();
		sb.append(getDocumentNo());
		//	: Total Lines = 123.00 (#1)
		sb.append(":")
			.append(" (#").append(getLines(false).length).append(")");
		//	 - Description
		if (getDescription() != null && getDescription().length() > 0)
			sb.append(" - ").append(getDescription());
		return sb.toString();
	}	//	getSummary

	/**
	 * 	Get Process Message
	 *	@return clear text error message
	 */
	public String getProcessMsg()
	{
		return m_processMsg;
	}	//	getProcessMsg

	/**
	 * 	Get Document Owner (Responsible)
	 *	@return AD_User_ID
	 */
	public int getDoc_User_ID()
	{
		return getSalesRep_ID();
	}	//	getDoc_User_ID

	/**
	 * 	Get Document Approval Amount
	 *	@return amount
	 */
	public BigDecimal getApprovalAmt()
	{
		return Env.ZERO;
	}	//	getApprovalAmt

	/**
	 * 	Get C_Currency_ID
	 *	@return Accounting Currency
	 */
	public int getC_Currency_ID ()
	{
		return Env.getContextAsInt(getCtx(),Env.C_CURRENCY_ID);
	}	//	getC_Currency_ID

	/**
	 * 	Document Status is Complete or Closed
	 *	@return true if CO, CL or RE
	 */
	public boolean isComplete()
	{
		String ds = getDocStatus();
		return DOCSTATUS_Completed.equals(ds)
			|| DOCSTATUS_Closed.equals(ds)
			|| DOCSTATUS_Reversed.equals(ds);
	}	//	isComplete

	/**
	 * For product with mix of No ASI and ASI inventory, this move Non ASI on hand to the new ASI created at shipment line or shipment line ma
	 * @param product
	 * @param M_Locator_ID shipment line locator id
	 * @param M_AttributeSetInstance_ID
	 * @param qty
	 * @param dateMaterialPolicy
	 * @param M_InOutLine_ID
	 * @param reversal
	 * @param trxName
	 * @return error doc status if there are any errors
	 */
	protected String moveOnHandToShipmentASI(MProduct product, int M_Locator_ID, int M_AttributeSetInstance_ID, BigDecimal qty,
			Timestamp dateMaterialPolicy, int M_InOutLine_ID, boolean reversal, String trxName) {
		if (qty.signum() == 0 || (qty.signum() < 0 && !reversal) || (qty.signum() > 0 && reversal))
			return null;
		if (M_AttributeSetInstance_ID == 0)
			return null;
		if (dateMaterialPolicy != null) {
			MStorageOnHand asi = MStorageOnHand.get(getCtx(), M_Locator_ID, product.getM_Product_ID(), M_AttributeSetInstance_ID, dateMaterialPolicy, trxName);
			if (asi != null && asi.getQtyOnHand().signum() != 0 && !reversal)
				return null;
			
			if (reversal) {
				if (!MStorageOnHand.add(getCtx(), M_Locator_ID, product.getM_Product_ID(), 0, qty.negate(), dateMaterialPolicy, trxName)) {
					String lastError = CLogger.retrieveErrorString("");
					m_processMsg = "Cannot move Inventory OnHand to Non ASI [" + product.getValue() + "] - " + lastError;
					return DocAction.STATUS_Invalid;
				}
				MTransaction trxFrom = new MTransaction (Env.getCtx(), getAD_Org_ID(), getMovementType(), M_Locator_ID, product.getM_Product_ID(), 0,
						qty.negate(), getMovementDate(), trxName);
				trxFrom.setM_InOutLine_ID(M_InOutLine_ID);
				if (!trxFrom.save()) {
					m_processMsg = "Transaction From not inserted (MA) [" + product.getValue() + "] - ";
					return DocAction.STATUS_Invalid;
				}
				if (!MStorageOnHand.add(getCtx(), M_Locator_ID, product.getM_Product_ID(), M_AttributeSetInstance_ID, qty, dateMaterialPolicy, trxName)) {
					String lastError = CLogger.retrieveErrorString("");
					m_processMsg = "Cannot move Inventory OnHand to Shipment ASI [" + product.getValue() + "] - " + lastError;
					return DocAction.STATUS_Invalid;
				}
				MTransaction trxTo = new MTransaction (Env.getCtx(), getAD_Org_ID(), getMovementType(), M_Locator_ID, product.getM_Product_ID(), M_AttributeSetInstance_ID,
						qty, getMovementDate(), trxName);
				trxTo.setM_InOutLine_ID(M_InOutLine_ID);
				if (!trxTo.save()) {
					m_processMsg = "Transaction To not inserted (MA) [" + product.getValue() + "] - ";
					return DocAction.STATUS_Invalid;
				}
			} else {
				return doMove(product, M_Locator_ID, M_AttributeSetInstance_ID, dateMaterialPolicy, qty, M_InOutLine_ID, reversal, trxName);
			}
		} else {
			BigDecimal totalASI = BigDecimal.ZERO;			
			MStorageOnHand[] storages = MStorageOnHand.getWarehouse(getCtx(), 0,
					product.getM_Product_ID(), M_AttributeSetInstance_ID, null,
					MClient.MMPOLICY_FiFo.equals(product.getMMPolicy()), false,
					M_Locator_ID, get_TrxName());
			for (MStorageOnHand onhand : storages) {
				totalASI = totalASI.add(onhand.getQtyOnHand());
			}
			if (!reversal && totalASI.signum() != 0) 
				return null;
			else if (reversal && (totalASI.compareTo(qty) < 0))
				return null;
			
			return doMove(product, M_Locator_ID, M_AttributeSetInstance_ID, dateMaterialPolicy, qty, M_InOutLine_ID, reversal, trxName);
		}
		
		return null;
	}

	private String doMove(MProduct product, int M_Locator_ID, int M_AttributeSetInstance_ID, Timestamp dateMaterialPolicy, BigDecimal qty,
			int M_InOutLine_ID, boolean reversal, String trxName) {
		MStorageOnHand[] storages;
		BigDecimal totalOnHand = BigDecimal.ZERO;
		Timestamp onHandDateMaterialPolicy = null;
		storages = MStorageOnHand.getWarehouse(getCtx(), 0,
				product.getM_Product_ID(), 0, null,
				MClient.MMPOLICY_FiFo.equals(product.getMMPolicy()), true,
				M_Locator_ID, get_TrxName());
		List<MStorageOnHand> nonASIList = new ArrayList<>();
		for (MStorageOnHand storage : storages) {
			if (storage.getM_AttributeSetInstance_ID() == 0) {
				totalOnHand = totalOnHand.add(storage.getQtyOnHand());
				nonASIList.add(storage);
			}
		}
		if (totalOnHand.compareTo(qty) >= 0 || reversal) {
			BigDecimal totalToMove = qty;
			for (MStorageOnHand onhand : nonASIList) {
				BigDecimal toMove = totalToMove;
				if (!reversal && toMove.compareTo(onhand.getQtyOnHand()) >= 0) {
					toMove = onhand.getQtyOnHand();							
				}
				if (!MStorageOnHand.add(getCtx(), M_Locator_ID, product.getM_Product_ID(), 0, toMove.negate(), onhand.getDateMaterialPolicy(), trxName)) {
					String lastError = CLogger.retrieveErrorString("");
					m_processMsg = "Cannot move Inventory OnHand to Non ASI [" + product.getValue() + "] - " + lastError;
					return DocAction.STATUS_Invalid;
				}
				MTransaction trxFrom = new MTransaction (Env.getCtx(), getAD_Org_ID(), getMovementType(), M_Locator_ID, product.getM_Product_ID(), 0,
						toMove.negate(), getMovementDate(), trxName);
				trxFrom.setM_InOutLine_ID(M_InOutLine_ID);
				if (!trxFrom.save()) {
					m_processMsg = "Transaction From not inserted (MA) [" + product.getValue() + "] - ";
					return DocAction.STATUS_Invalid;
				}
				onHandDateMaterialPolicy = onhand.getDateMaterialPolicy();
				totalToMove = totalToMove.subtract(toMove);
				if ((!reversal && totalToMove.signum() <= 0) || (reversal && totalToMove.signum() >= 0))
					break;
			}
			if (!MStorageOnHand.add(getCtx(), M_Locator_ID, product.getM_Product_ID(), M_AttributeSetInstance_ID, qty, 
					(dateMaterialPolicy != null ? dateMaterialPolicy : onHandDateMaterialPolicy), trxName)) {
				String lastError = CLogger.retrieveErrorString("");
				m_processMsg = "Cannot move Inventory OnHand to Shipment ASI [" + product.getValue() + "] - " + lastError;
				return DocAction.STATUS_Invalid;
			}
			MTransaction trxTo = new MTransaction (Env.getCtx(), getAD_Org_ID(), getMovementType(), M_Locator_ID, product.getM_Product_ID(), M_AttributeSetInstance_ID,
					qty, getMovementDate(), trxName);
			trxTo.setM_InOutLine_ID(M_InOutLine_ID);
			if (!trxTo.save()) {
				m_processMsg = "Transaction To not inserted (MA) [" + product.getValue() + "] - ";
				return DocAction.STATUS_Invalid;
			}
		}
		return null;
	}
	
	/**
	 * Create Line from orderline/invoiceline/rmaline
	 * @param C_OrderLine_ID
	 * @param C_InvoiceLine_ID
	 * @param M_RMALine_ID
	 * @param M_Product_ID
	 * @param C_UOM_ID
	 * @param Qty
	 * @param M_Locator_ID
	 */
	public void createLineFrom(int C_OrderLine_ID, int C_InvoiceLine_ID, int M_RMALine_ID, 
			int M_Product_ID, int C_UOM_ID, BigDecimal Qty, int M_Locator_ID)
	{
		MInvoiceLine il = null;
		if (C_InvoiceLine_ID != 0)
			il = new MInvoiceLine (Env.getCtx(), C_InvoiceLine_ID, get_TrxName());
		
		MInOutLine iol = new MInOutLine (this);
		iol.setM_Product_ID(M_Product_ID, C_UOM_ID);	//	Line UOM
		iol.setQty(Qty);							//	Movement/Entered
		//
		MOrderLine ol = null;
		MRMALine rmal = null;
		if (C_OrderLine_ID != 0)
		{
			iol.setC_OrderLine_ID(C_OrderLine_ID);
			ol = new MOrderLine (Env.getCtx(), C_OrderLine_ID, get_TrxName());
			if (ol.getQtyEntered().compareTo(ol.getQtyOrdered()) != 0)
			{
				iol.setMovementQty(Qty
						.multiply(ol.getQtyOrdered())
						.divide(ol.getQtyEntered(), 12, RoundingMode.HALF_UP));
				iol.setC_UOM_ID(ol.getC_UOM_ID());
			}
			iol.setM_AttributeSetInstance_ID(ol.getM_AttributeSetInstance_ID());
			iol.setDescription(ol.getDescription());
			//
			iol.setC_Project_ID(ol.getC_Project_ID());
			iol.setC_ProjectPhase_ID(ol.getC_ProjectPhase_ID());
			iol.setC_ProjectTask_ID(ol.getC_ProjectTask_ID());
			iol.setC_Activity_ID(ol.getC_Activity_ID());
			iol.setC_Campaign_ID(ol.getC_Campaign_ID());
			iol.setAD_OrgTrx_ID(ol.getAD_OrgTrx_ID());
			iol.setUser1_ID(ol.getUser1_ID());
			iol.setUser2_ID(ol.getUser2_ID());
		}
		else if (il != null)
		{
			if (il.getQtyEntered().compareTo(il.getQtyInvoiced()) != 0)
			{
				iol.setMovementQty(Qty
						.multiply(il.getQtyInvoiced())
						.divide(il.getQtyEntered(), 12, RoundingMode.HALF_UP));
				iol.setC_UOM_ID(il.getC_UOM_ID());
			}
			iol.setDescription(il.getDescription());
			iol.setC_Project_ID(il.getC_Project_ID());
			iol.setC_ProjectPhase_ID(il.getC_ProjectPhase_ID());
			iol.setC_ProjectTask_ID(il.getC_ProjectTask_ID());
			iol.setC_Activity_ID(il.getC_Activity_ID());
			iol.setC_Campaign_ID(il.getC_Campaign_ID());
			iol.setAD_OrgTrx_ID(il.getAD_OrgTrx_ID());
			iol.setUser1_ID(il.getUser1_ID());
			iol.setUser2_ID(il.getUser2_ID());
		}
		else if (M_RMALine_ID != 0)
		{
			rmal = new MRMALine(Env.getCtx(), M_RMALine_ID, get_TrxName());
			iol.setM_RMALine_ID(M_RMALine_ID);
			iol.setQtyEntered(Qty);
			iol.setDescription(rmal.getDescription());
			iol.setM_AttributeSetInstance_ID(rmal.getM_AttributeSetInstance_ID());
			iol.setC_Project_ID(rmal.getC_Project_ID());
			iol.setC_ProjectPhase_ID(rmal.getC_ProjectPhase_ID());
			iol.setC_ProjectTask_ID(rmal.getC_ProjectTask_ID());
			iol.setC_Activity_ID(rmal.getC_Activity_ID());
			iol.setAD_OrgTrx_ID(rmal.getAD_OrgTrx_ID());
			iol.setUser1_ID(rmal.getUser1_ID());
			iol.setUser2_ID(rmal.getUser2_ID());
		}

		//	Charge
		if (M_Product_ID == 0)
		{
			if (ol != null && ol.getC_Charge_ID() != 0)			//	from order
				iol.setC_Charge_ID(ol.getC_Charge_ID());
			else if (il != null && il.getC_Charge_ID() != 0)	//	from invoice
				iol.setC_Charge_ID(il.getC_Charge_ID());
			else if (rmal != null && rmal.getC_Charge_ID() != 0) // from rma
				iol.setC_Charge_ID(rmal.getC_Charge_ID());
		}
		// Set locator
		iol.setM_Locator_ID(M_Locator_ID);
		iol.saveEx();
		//	Create Invoice Line Link
		if (il != null)
		{
			il.setM_InOutLine_ID(iol.getM_InOutLine_ID());
			il.saveEx();
		}
	}
	
	/**
	 * Update from order/invoice/rma
	 * - if linked to another order/invoice/rma - remove link
	 * - if no link set it
	 * @param order
	 * @param invoice
	 * @param rma
	 */
	public void updateFrom(MOrder order, MInvoice invoice, MRMA rma)
	{
		if (order != null && order.getC_Order_ID() != 0)
		{
			setC_Order_ID (order.getC_Order_ID());
			setAD_OrgTrx_ID(order.getAD_OrgTrx_ID());
			setC_Project_ID(order.getC_Project_ID());
			setC_Campaign_ID(order.getC_Campaign_ID());
			setC_Activity_ID(order.getC_Activity_ID());
			setSalesRep_ID (order.getSalesRep_ID());
			setUser1_ID(order.getUser1_ID());
			setUser2_ID(order.getUser2_ID());

			if ( order.isDropShip() )
			{
				setM_Warehouse_ID( order.getM_Warehouse_ID() );
				setIsDropShip(order.isDropShip());
				setDropShip_BPartner_ID(order.getDropShip_BPartner_ID());
				setDropShip_Location_ID(order.getDropShip_Location_ID());
				setDropShip_User_ID(order.getDropShip_User_ID());
				if (MOrder.DELIVERYVIARULE_Shipper.equals(order.getDeliveryViaRule()) && order.getM_Shipper_ID() > 0)
				{
					setDeliveryViaRule(order.getDeliveryViaRule());
					setM_Shipper_ID(order.getM_Shipper_ID());
				}
			}
		}
		if (invoice != null && invoice.getC_Invoice_ID() != 0)
		{
			if (getC_Order_ID() == 0)
				setC_Order_ID (invoice.getC_Order_ID());
			setC_Invoice_ID (invoice.getC_Invoice_ID());
			setAD_OrgTrx_ID(invoice.getAD_OrgTrx_ID());
			setC_Project_ID(invoice.getC_Project_ID());
			setC_Campaign_ID(invoice.getC_Campaign_ID());
			setC_Activity_ID(invoice.getC_Activity_ID());
			setUser1_ID(invoice.getUser1_ID());
			setUser2_ID(invoice.getUser2_ID());
		}
		if (rma != null && rma.getM_RMA_ID() != 0)
		{
			MInOut originalIO = rma.getShipment();
			setIsSOTrx(rma.isSOTrx());
			setC_Order_ID(0);
			setC_Invoice_ID(0);
			setM_RMA_ID(rma.getM_RMA_ID());
			setAD_OrgTrx_ID(originalIO.getAD_OrgTrx_ID());
			setC_Project_ID(originalIO.getC_Project_ID());
			setC_Campaign_ID(originalIO.getC_Campaign_ID());
			setC_Activity_ID(originalIO.getC_Activity_ID());
			setUser1_ID(originalIO.getUser1_ID());
			setUser2_ID(originalIO.getUser2_ID());
		}
		saveEx();
	}
}	//	MInOut

package com.citizenme.integration.ofbiz.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;
import org.ofbiz.service.ServiceUtil;

import com.citizenme.integration.ofbiz.OFBizRequest;
import com.citizenme.integration.ofbiz.helper.RequestHelper;
import com.citizenme.integration.ofbiz.model.PanelSalesOrder;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;


@Path("/createpanelorder")
public class CreatePanelOrder {

  private static Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  
  private GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
  private LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default",delegator);
  
  public static String getOrderItemSequence(int seq) {
    return String.format("%05d", seq);
  }
  
  @POST
  @Consumes("application/json")
  @Produces("application/json")
  //@Path("{partyId}")
  //@PathParam("partyId") String partyId,
  public Response execute(InputStream requestBodyStream) {
    
    try {
      OFBizRequest ofbizRequest = RequestHelper.deserializeOFBizRequest(requestBodyStream);
      
      Set<ConstraintViolation<OFBizRequest>> constraintViolations = validator.validate(ofbizRequest);
      
      if (constraintViolations.size() > 0)
        throw new RuntimeException("Invalid input: " + constraintViolations.toString());
      
      PanelSalesOrder order = (PanelSalesOrder) ofbizRequest.getRequestParameter();
      
      Map<String, Object> orderRequestMap = UtilMisc.<String, Object>toMap(
          "login.username", ofbizRequest.getLogin()
        , "login.password", ofbizRequest.getPassword()
        , "partyId", order.getClientPartyId()
        , "orderTypeId", "SALES_ORDER"
        , "currencyUom", order.getCurrency()
        , "productStoreId", "10000" // CitizenMe store
        , "orderId", order.getOrderId()
        , "placingCustomerPartyId", order.getClientPartyId()
        , "endUserCustomerPartyId", order.getClientPartyId()
        , "billToCustomerPartyId", order.getClientPartyId()
        , "billFromVendorPartyId", "Company"
      );

      // Add order payment info - presumably where to send invoice
      List<GenericValue> orderPaymentInfo = new LinkedList<GenericValue>();
//    GenericValue orderContactMech = delegator.makeValue("OrderContactMech", UtilMisc.toMap("contactMechId", "9015", "contactMechPurposeTypeId", "BILLING_LOCATION"));
//    orderPaymentInfo.add(orderContactMech);

      // Add order payment preference hard-coded for now as PAYPAL
      GenericValue orderPaymentPreference = delegator.makeValue(
        "OrderPaymentPreference"
      , UtilMisc.toMap(
        "paymentMethodTypeId", "EXT_PAYPAL"
      // , "paymentMethodId", "9015"
      // , "statusId", "PAYMENT_NOT_AUTH"
      // , "overflowFlag", "N"
      // , "maxAmount", order.getOrderTotal()
      ));

      orderPaymentInfo.add(orderPaymentPreference);
      orderRequestMap.put("orderPaymentInfo", orderPaymentInfo);

      int orderItemSeqId = 1;
      
      List<GenericValue> orderItems = new LinkedList<GenericValue>();

      String shipGroupSeqId = "00001";
      
      List<GenericValue> orderItemShipGroupInfo = new LinkedList<GenericValue>();

      GenericValue orderItemShipGroup = delegator.makeValue(
        "OrderItemShipGroup"
      , UtilMisc.toMap("carrierPartyId", "_NA_"
      , "isGift", "N"
      , "shipGroupSeqId", shipGroupSeqId
      , "shipmentMethodTypeId", "NO_SHIPPING"
      ));
      orderItemShipGroupInfo.add(orderItemShipGroup);

      
      GenericValue orderItem = null;
      GenericValue orderItemAdjustment = null;
      GenericValue orderItemShipGroupAssoc = null;

      // Create panel entry line item + VAT adjustment
      orderItem = delegator.makeValue(
        "OrderItem"
      , UtilMisc.toMap(
          "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
        , "orderItemTypeId", "PRODUCT_ORDER_ITEM"
        , "prodCatalogId", "CitizenMe"
        , "productId", "PANEL-ENTRY"
        , "quantity", order.getPaidParticipants()
        , "selectedAmount", BigDecimal.ZERO
        , "isPromo", "N"
        , "isModifiedPrice", "N"
        , "unitPrice", order.getParticipantUnitFee()
        , "unitListPrice", order.getParticipantUnitFee()
        , "statusId", "ITEM_CREATED" // "ITEM_APPROVED"
        , "itemDescription", "Panel Entry"
          ));
      orderItems.add(orderItem);
      
      orderItemShipGroupAssoc = delegator.makeValue(
        "OrderItemShipGroupAssoc"
      , UtilMisc.toMap("orderItemSeqId", getOrderItemSequence(orderItemSeqId)
      , "quantity", order.getPaidParticipants()
      , "shipGroupSeqId", shipGroupSeqId
      ));
      orderItemShipGroupInfo.add(orderItemShipGroupAssoc);
      
      orderItemAdjustment = delegator.makeValue(
        "OrderAdjustment"
      , UtilMisc.toMap(
        "orderAdjustmentTypeId", "VAT_TAX"
      , "taxAuthGeoId", "GBR"
      , "taxAuthPartyId", "10020" // UK_HMRC
      , "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
      , "overrideGlAccountId", "224301" // VAT COLLECTED UK
      , "primaryGeoId", "GBR" // Great Britain
      , "shipGroupSeqId", shipGroupSeqId
      , "sourcePercentage", order.getVatPct()));
      // orderAdjustments.add(orderAdjustment);
      orderItemShipGroupInfo.add(orderItemAdjustment);

      orderItemSeqId++;
      
      if ("PAID".equals(order.getPanelType())) {
      
        // Create panel fee line item + VAT adjustment
        orderItem = delegator.makeValue(
          "OrderItem"
        , UtilMisc.toMap(
            "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
          , "orderItemTypeId", "PRODUCT_ORDER_ITEM"
          , "prodCatalogId", "CitizenMe"
          , "productId", "PANEL-FEE"
          , "quantity", BigDecimal.ONE
          , "selectedAmount", BigDecimal.ZERO
          , "isPromo", "N"
          , "isModifiedPrice", "N"
          , "unitPrice", order.getPanelFee()
          , "unitListPrice", order.getPanelFee()
          , "statusId", "ITEM_CREATED" // "ITEM_APPROVED"
          , "itemDescription", "Panel Fee"
            ));
        orderItems.add(orderItem);

        orderItemShipGroupAssoc = delegator.makeValue(
          "OrderItemShipGroupAssoc"
        , UtilMisc.toMap("orderItemSeqId", getOrderItemSequence(orderItemSeqId)
        , "quantity", BigDecimal.ONE
        , "shipGroupSeqId", shipGroupSeqId
        ));
        orderItemShipGroupInfo.add(orderItemShipGroupAssoc);
        
        orderItemAdjustment = delegator.makeValue(
          "OrderAdjustment"
        , UtilMisc.toMap(
          "orderAdjustmentTypeId", "VAT_TAX"
        , "taxAuthGeoId", "GBR"
        , "taxAuthPartyId", "10020" // UK_HMRC
        , "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
        , "overrideGlAccountId", "224301" // VAT COLLECTED UK
        , "primaryGeoId", "GBR" // Great Britain
        , "shipGroupSeqId", shipGroupSeqId
        , "sourcePercentage", order.getVatPct()));
        // orderAdjustments.add(orderAdjustment);
        orderItemShipGroupInfo.add(orderItemAdjustment);
        
        orderItemSeqId++;
      }

      // Create transaction fee line item + VAT adjustment
      orderItem = delegator.makeValue(
          "OrderItem"
        , UtilMisc.toMap(
            "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
          , "orderItemTypeId", "PRODUCT_ORDER_ITEM"
          , "prodCatalogId", "CitizenMe"
          , "productId", "TRANS-FEE"
          , "quantity", BigDecimal.ONE
          , "selectedAmount", BigDecimal.ZERO
          , "isPromo", "N"
          , "isModifiedPrice", "N"
          , "unitPrice", order.getTransactionFee()
          , "unitListPrice", order.getTransactionFee()
          , "statusId", "ITEM_CREATED" // "ITEM_APPROVED"
          , "itemDescription", "Transaction Fee"
            ));
      orderItems.add(orderItem);
          
      orderItemShipGroupAssoc = delegator.makeValue(
        "OrderItemShipGroupAssoc"
      , UtilMisc.toMap("orderItemSeqId", getOrderItemSequence(orderItemSeqId)
      , "quantity", BigDecimal.ONE
      , "shipGroupSeqId", shipGroupSeqId
      ));
      orderItemShipGroupInfo.add(orderItemShipGroupAssoc);
        
      orderItemAdjustment = delegator.makeValue(
          "OrderAdjustment"
        , UtilMisc.toMap(
          "orderAdjustmentTypeId", "VAT_TAX"
        , "taxAuthGeoId", "GBR"
        , "taxAuthPartyId", "10020" // UK_HMRC
        , "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
        , "overrideGlAccountId", "224301" // VAT COLLECTED UK
        , "primaryGeoId", "GBR" // Great Britain
        , "shipGroupSeqId", shipGroupSeqId
        , "sourcePercentage", order.getVatPct()));
      // orderAdjustments.add(orderAdjustment);
      orderItemShipGroupInfo.add(orderItemAdjustment);
      
      orderRequestMap.put("orderItems", orderItems);
      // orderRequestMap.put("orderAdjustments", orderAdjustments);
      orderRequestMap.put("orderItemShipGroupInfo", orderItemShipGroupInfo);
      
      List<GenericValue> orderTerms = new LinkedList<GenericValue>();
      orderRequestMap.put("orderTerms", orderTerms);
      
      List<GenericValue> orderAdjustments = new LinkedList<GenericValue>();
      orderRequestMap.put("orderAdjustments", orderAdjustments);
      
      //             List<GenericValue> emails = delegator.findByAnd("OrderContactMech", UtilMisc.toMap("orderId", orderId, "contactMechPurposeTypeId", "ORDER_EMAIL"), null, false);

      Map<String, Object> result = null;
      
      result = dispatcher.runSync("storeOrder", orderRequestMap);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        return Response.serverError().entity(createResponse(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }

      Map<String, Object> invoiceRequestMap = new HashMap<String, Object>();
      invoiceRequestMap.put("login.username", ofbizRequest.getLogin());
      invoiceRequestMap.put("login.password", ofbizRequest.getPassword());
      invoiceRequestMap.put("orderId", order.getOrderId());

//      List<GenericValue> orderItemsX = delegator.findByAnd("OrderItem", UtilMisc.toMap("orderId", order.getOrderId()), UtilMisc.toList("orderItemSeqId"), false);
//      if (orderItemsX.size() > 0) {
//        invoiceRequestMap.put("billItems", orderItems);
//      }
      
      result = dispatcher.runSync("createInvoiceForOrderAllItems", invoiceRequestMap);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        return Response.serverError().entity(createResponse(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }
      
      return Response.ok(createResponse(getClass().getName(), true, "OK")).type("application/json").build();

    } catch (IOException | RuntimeException | GenericServiceException e) {
      Debug.logError(e, getClass().getName());
      return Response.serverError().entity(createResponse(getClass().getName(), false, e.toString())).build();
    }
  }
}

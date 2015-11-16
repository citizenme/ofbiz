package com.citizenme.integration.ofbiz.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

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
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.transaction.GenericTransactionException;
import org.ofbiz.entity.transaction.TransactionUtil;
import org.ofbiz.order.order.OrderChangeHelper;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;
import org.ofbiz.service.ServiceUtil;

import com.citizenme.integration.ofbiz.OFBizRequest;
import com.citizenme.integration.ofbiz.helper.Config;
import com.citizenme.integration.ofbiz.helper.ConfigHelper;
import com.citizenme.integration.ofbiz.helper.ContactMechHelper;
import com.citizenme.integration.ofbiz.helper.RequestHelper;
import com.citizenme.integration.ofbiz.helper.TaxAuthority;
import com.citizenme.integration.ofbiz.model.PanelSalesOrder;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;


@Path("/createpanelsalesorder")
public class CreatePanelSalesOrderResource {

  private static Config config = ConfigHelper.getConfig();
  
  private static Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  
  private GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
  private LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default",delegator);
  
  public static String getOrderItemSequence(int seq) {
    return String.format("%05d", seq);
  }

  
  private final static String BILLING_LOCATION_PURPOSE_TYPE_ID = "BILLING_LOCATION";
  private final static String ORDER_EMAIL_PURPOSE_TYPE_ID = "ORDER_EMAIL";
  private final static String BILLING_EMAIL_PURPOSE_TYPE_ID = "BILLING_EMAIL";
  
  @POST
  @Consumes("application/json")
  @Produces("application/json")
  //@Path("{partyId}")
  //@PathParam("partyId") String partyId,
  public Response execute(InputStream requestBodyStream) throws GenericTransactionException {
    
    Map<String, Object> result = null;

    try {
      OFBizRequest ofbizRequest = RequestHelper.deserializeOFBizRequest(requestBodyStream);
      
      Set<ConstraintViolation<OFBizRequest>> constraintViolations = validator.validate(ofbizRequest);
      
      if (constraintViolations.size() > 0)
        throw new RuntimeException("Invalid input: " + constraintViolations.toString());
      
      PanelSalesOrder order = (PanelSalesOrder) ofbizRequest.getRequestParameter();

      String countryGeoId = order.getBillingLocation().getCountryGeoId();
      
      TaxAuthority taxAuthority = config.getTaxAuthorities().get(countryGeoId);

      if (TransactionUtil.begin() == false)
        throw new RuntimeException("Transaction is already unexpectedly started");

      // First, get userLogin for those calls that require it
//      GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", "system"), true);
      GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", ofbizRequest.getLogin()), true);
      
      // Create order/billing email as part of client organisation
      result = ContactMechHelper.findOrCreatePartyContactMechEmailAddress (
          ofbizRequest.getLogin()
        , ofbizRequest.getPassword()
        , order.getClientAgentPartyId()
        , order.getBillingEmail()
        , ORDER_EMAIL_PURPOSE_TYPE_ID
        , dispatcher
      );

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }

      String orderEmailContactMechId = (String) result.get("contactMechId");

      // Create billing email as part of client agent
      result = ContactMechHelper.findOrCreatePartyContactMechEmailAddress (
          ofbizRequest.getLogin()
        , ofbizRequest.getPassword()
        , order.getClientAgentPartyId()
        , order.getBillingEmail()
        , BILLING_EMAIL_PURPOSE_TYPE_ID
        , dispatcher
      );
      
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }
      
      String billingEmailContactMechId = (String) result.get("contactMechId");

      // Create billing location as part of party and attach same billing location to order
      result = ContactMechHelper.findOrCreatePartyContactMechPostalAddress (
          ofbizRequest.getLogin()
        , ofbizRequest.getPassword()
        , order.getClientOrganisationPartyId()
        , order.getBillingLocation()
        , BILLING_LOCATION_PURPOSE_TYPE_ID
        , dispatcher
      );
      
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }
      
      String billingLocationContactMechId = (String) result.get("contactMechId");
      
      Map<String, Object> orderRequestMap = UtilMisc.<String, Object>toMap(
          "login.username", ofbizRequest.getLogin()
        , "login.password", ofbizRequest.getPassword()
        , "partyId", order.getClientOrganisationPartyId()
        , "orderTypeId", "SALES_ORDER"
        , "currencyUom", order.getCurrency()
        , "productStoreId", config.getParameters().get("productStoreId") // CitizenMe store
        , "orderId", order.getOrderId()
        , "placingCustomerPartyId", order.getClientAgentPartyId()
        , "endUserCustomerPartyId", order.getClientOrganisationPartyId()
        , "billToCustomerPartyId", order.getClientOrganisationPartyId()
        , "billFromVendorPartyId", "Company"
        , "originFacilityId", config.getParameters().get("originFacilityId")
        , "orderName", "" // Just empty name for now
        , "webSiteId", "OrderEntry" // Required in order to later send order notifications?!?!
      );

      // Add order payment info
      List<GenericValue> orderPaymentInfo = new LinkedList<GenericValue>();
      
      GenericValue orderBillingLocationContactMech = delegator.makeValue("OrderContactMech", UtilMisc.toMap("contactMechId", billingLocationContactMechId, "contactMechPurposeTypeId", BILLING_LOCATION_PURPOSE_TYPE_ID));
      orderPaymentInfo.add(orderBillingLocationContactMech);

      GenericValue orderOrderEmailContactMech = delegator.makeValue("OrderContactMech", UtilMisc.toMap("contactMechId", orderEmailContactMechId, "contactMechPurposeTypeId", ORDER_EMAIL_PURPOSE_TYPE_ID));
      orderPaymentInfo.add(orderOrderEmailContactMech);

      GenericValue orderBillingEmailContactMech = delegator.makeValue("OrderContactMech", UtilMisc.toMap("contactMechId", billingEmailContactMechId, "contactMechPurposeTypeId", BILLING_EMAIL_PURPOSE_TYPE_ID));
      orderPaymentInfo.add(orderBillingEmailContactMech);

      // Add order payment preference hard-coded for now as PAYPAL
      GenericValue orderPaymentPreference = delegator.makeValue(
          "OrderPaymentPreference"
        , UtilMisc.toMap(
            "paymentMethodTypeId", "EXT_PAYPAL"
          , "maxAmount", order.getOrderTotal()
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
        , "contactMechId", billingLocationContactMechId // OFBiz bug: if there's no shipping location then VAT is not properly added to order
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
        , "prodCatalogId", config.getParameters().get("prodCatalogId")
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
      
      if (! order.isVatExempt()) {
        orderItemAdjustment = delegator.makeValue(
            "OrderAdjustment"
          , UtilMisc.toMap(
            "orderAdjustmentTypeId", taxAuthority.getOrderAdjustmentTypeId()
          , "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
          , "overrideGlAccountId", taxAuthority.getOverrideGlAccountId()
          , "primaryGeoId", taxAuthority.getGeoId()
          , "shipGroupSeqId", shipGroupSeqId
          , "sourcePercentage", order.getVatPct()
          , "taxAuthGeoId", taxAuthority.getGeoId()
          , "taxAuthPartyId", taxAuthority.getPartyId()
          , "amount", order.getParticipantUnitFee().multiply(order.getPaidParticipants()).multiply(order.getVatPct()).divide(new BigDecimal(100))
          , "comments", taxAuthority.getComments()
          , "taxAuthorityRateSeqId", taxAuthority.getRateSeqId()
        ));
        orderItemShipGroupInfo.add(orderItemAdjustment);
      }
      
      orderItemSeqId++;
      
      if ("PAID".equals(order.getPanelType())) {
      
        // Create panel fee line item + VAT adjustment
        orderItem = delegator.makeValue(
            "OrderItem"
          , UtilMisc.toMap(
            "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
          , "orderItemTypeId", "PRODUCT_ORDER_ITEM"
          , "prodCatalogId", config.getParameters().get("prodCatalogId")
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
        
        if (! order.isVatExempt()) {
          orderItemAdjustment = delegator.makeValue(
              "OrderAdjustment"
            , UtilMisc.toMap(
              "orderAdjustmentTypeId", taxAuthority.getOrderAdjustmentTypeId()
            , "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
            , "overrideGlAccountId", taxAuthority.getOverrideGlAccountId()
            , "primaryGeoId", taxAuthority.getGeoId()
            , "shipGroupSeqId", shipGroupSeqId
            , "sourcePercentage", order.getVatPct()
            , "taxAuthGeoId", taxAuthority.getGeoId()
            , "taxAuthPartyId", taxAuthority.getPartyId()
            , "amount", order.getPanelFee().multiply(order.getVatPct()).divide(new BigDecimal(100))
            , "comments", taxAuthority.getComments()
            , "taxAuthorityRateSeqId", taxAuthority.getRateSeqId()
          ));
          orderItemShipGroupInfo.add(orderItemAdjustment);
        }

        orderItemSeqId++;
      }

      // Create transaction fee line item + VAT adjustment
      orderItem = delegator.makeValue(
          "OrderItem"
        , UtilMisc.toMap(
          "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
        , "orderItemTypeId", "PRODUCT_ORDER_ITEM"
        , "prodCatalogId", config.getParameters().get("prodCatalogId")
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

      if (! order.isVatExempt()) {
        orderItemAdjustment = delegator.makeValue(
          "OrderAdjustment"
        , UtilMisc.toMap(
            "orderAdjustmentTypeId", taxAuthority.getOrderAdjustmentTypeId()
          , "orderItemSeqId", getOrderItemSequence(orderItemSeqId)
          , "overrideGlAccountId", taxAuthority.getOverrideGlAccountId()
          , "primaryGeoId", taxAuthority.getGeoId()
          , "shipGroupSeqId", shipGroupSeqId
          , "sourcePercentage", order.getVatPct()
          , "taxAuthGeoId", taxAuthority.getGeoId()
          , "taxAuthPartyId", taxAuthority.getPartyId()
          , "amount", order.getTransactionFee().multiply(order.getVatPct()).divide(new BigDecimal(100))
          , "comments", taxAuthority.getComments()
          , "taxAuthorityRateSeqId", taxAuthority.getRateSeqId()
        ));
        orderItemShipGroupInfo.add(orderItemAdjustment);
      }

      orderRequestMap.put("orderItems", orderItems);
      orderRequestMap.put("orderItemShipGroupInfo", orderItemShipGroupInfo);
      
      // No order terms
      List<GenericValue> orderTerms = new LinkedList<GenericValue>();
      orderRequestMap.put("orderTerms", orderTerms);
      
      // No order "total" adjustments
      List<GenericValue> orderAdjustments = new LinkedList<GenericValue>();
      orderRequestMap.put("orderAdjustments", orderAdjustments);
      
      result = dispatcher.runSync("storeOrder", orderRequestMap);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }

//      // Now email the invoice
//      Locale locale = new Locale((String) config.getParameter("locale"));
//
//      Map<String, Object> bodyParameters = UtilMisc.<String, Object>toMap(
//          "orderId", order.getOrderId()
//        , "userLogin", userLogin
//        , "locale", locale);
//
//      Map<String, Object> sendMap = UtilMisc.<String, Object>toMap(
//        "sendFrom", (String) config.getParameter("invoiceEmailFrom")
//      , "sendTo", "morten@citizenme.com"
//      , "bodyScreenUri", "component://ecommerce/widget/EmailOrderScreens.xml#OrderConfirmNotice"
//      , "subject", String.format((String) config.getParameter("invoiceEmailSubject"), order.getOrderId())
//      , "bodyText", (String) config.getParameter("invoiceEmailBodyText")
//      , "timeZone",  TimeZone.getTimeZone((String) config.getParameter("timeZone"))
//      , "locale", locale
//      , "userLogin", userLogin
//      , "bodyParameters", bodyParameters
//      , "contentType", "text/html" 
//      );

//    result = dispatcher.runSync("sendMail", sendMap);
//    if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
//      TransactionUtil.rollback();
//      return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
//    }

      // Don't let notification email below fail transaction - opportunistic best-effort email only ;-)
      TransactionUtil.commit();

      dispatcher.runAsync("sendOrderConfirmation", UtilMisc.toMap("orderId", order.getOrderId(), "userLogin", userLogin, "temporaryAnonymousUserLogin", userLogin));

      return Response.ok(createOFBizResponseString(getClass().getName(), true, "OK")).type("application/json").build();

    } catch (IOException | RuntimeException | GenericServiceException | GenericEntityException e) {
      Debug.logError(e, getClass().getName());
      TransactionUtil.rollback(e);
      return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, e.toString())).build();
    }
  }
}

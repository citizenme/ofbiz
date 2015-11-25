package com.citizenme.integration.ofbiz.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
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
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.transaction.GenericTransactionException;
import org.ofbiz.entity.transaction.TransactionUtil;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;
import org.ofbiz.service.ServiceUtil;

import com.citizenme.integration.ofbiz.OFBizRequest;
import com.citizenme.integration.ofbiz.helper.Config;
import com.citizenme.integration.ofbiz.helper.ConfigHelper;
import com.citizenme.integration.ofbiz.helper.RequestHelper;
import com.citizenme.integration.ofbiz.helper.TaxAuthority;
import com.citizenme.integration.ofbiz.model.PanelPurchaseOrder;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;


@Path("/createpanelpurchaseorder")
public class CreatePanelPurchaseOrderResource {

  private static Config config = ConfigHelper.getConfig();
  
  private static Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  
  private GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
  private LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default",delegator);
  
  public static String getOrderItemSequence(int seq) {
    return String.format("%05d", seq);
  }
  
  private final static String ORDER_EMAIL_PURPOSE_TYPE_ID = "ORDER_EMAIL";
  private final static String SHIPPING_LOCATION_PURPOSE_TYPE_ID = "SHIPPING_LOCATION";

  @POST
  @Consumes("application/json")
  @Produces("application/json")
  public Response execute(InputStream requestBodyStream) throws GenericTransactionException {
    
    Map<String, Object> result = null;

    try {
      OFBizRequest ofbizRequest = RequestHelper.deserializeOFBizRequest(requestBodyStream);
      
      Set<ConstraintViolation<OFBizRequest>> constraintViolations = validator.validate(ofbizRequest);
      
      if (constraintViolations.size() > 0)
        throw new RuntimeException("Invalid input: " + constraintViolations.toString());
      
      PanelPurchaseOrder order = (PanelPurchaseOrder) ofbizRequest.getRequestParameter();

      // Local CitizenMe tax jurisdiction
      String countryGeoId = (String) config.getParameter("localTaxJurisdictionGeoId").toString();
      
      TaxAuthority taxAuthority = config.getTaxAuthorities().get(countryGeoId);

      if (TransactionUtil.begin() == false)
        throw new RuntimeException("Transaction is already unexpectedly started");

      // First, get userLogin for those calls that require it
   //   GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", ofbizRequest.getLogin()), true);

      Map<String, Object> orderRequestMap = UtilMisc.<String, Object>toMap(
          "login.username", ofbizRequest.getLogin()
        , "login.password", ofbizRequest.getPassword()
        , "partyId", order.getCitizenPartyId()
        , "orderTypeId", "PURCHASE_ORDER"
        , "currencyUom", order.getCurrency()
        , "productStoreId", config.getParameters().get("productStoreId") // CitizenMe store
        , "orderId", order.getOrderId()
      //  , "placingCustomerPartyId", config.getParameter("companyPartyId")
        , "endUserCustomerPartyId", config.getParameter("companyPartyId") // TODO: This should probably be client organisation
        , "billToCustomerPartyId", config.getParameter("companyPartyId")
        , "billFromVendorPartyId", order.getCitizenPartyId()
        , "supplierAgentPartyId", order.getCitizenPartyId()
        , "originFacilityId", config.getParameters().get("originFacilityId")
        , "orderName", "" // Just empty name for now
        , "webSiteId", "OrderEntry" // Required in order to later send order notifications?!?!
      );

      // Add order payment info
      List<GenericValue> orderPaymentInfo = new LinkedList<GenericValue>();
      
      GenericValue orderShippingLocationContactMech = delegator.makeValue("OrderContactMech", UtilMisc.toMap("contactMechId", config.getParameter("purchaseOrderLocationContactMechId"), "contactMechPurposeTypeId", SHIPPING_LOCATION_PURPOSE_TYPE_ID));
      orderPaymentInfo.add(orderShippingLocationContactMech);

      GenericValue orderOrderEmailContactMech = delegator.makeValue("OrderContactMech", UtilMisc.toMap("contactMechId", config.getParameter("purchaseOrderEmailContactMechId"), "contactMechPurposeTypeId", ORDER_EMAIL_PURPOSE_TYPE_ID));
      orderPaymentInfo.add(orderOrderEmailContactMech);

//      GenericValue orderBillingEmailContactMech = delegator.makeValue("OrderContactMech", UtilMisc.toMap("contactMechId", config.getParameter("purchaseOrderEmailContactMechId"), "contactMechPurposeTypeId", BILLING_EMAIL_PURPOSE_TYPE_ID));
//      orderPaymentInfo.add(orderBillingEmailContactMech);

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
        , "contactMechId", config.getParameter("purchaseOrderLocationContactMechId") // OFBiz bug: if there's no shipping location then VAT is not properly added to order
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
        , "quantity", BigDecimal.ONE
        , "selectedAmount", BigDecimal.ZERO
        , "isPromo", "N"
        , "isModifiedPrice", "N"
        , "unitPrice", order.getPanelFee()
        , "unitListPrice", order.getPanelFee()
        , "statusId", "ITEM_CREATED" // "ITEM_APPROVED"
        , "itemDescription", "Panel Entry"
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
          , "amount", order.getVatAmount()
          , "comments", taxAuthority.getComments()
          , "taxAuthorityRateSeqId", taxAuthority.getRateSeqId()
        ));
        orderItemShipGroupInfo.add(orderItemAdjustment);
      }
      
      orderItemSeqId++;

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

      TransactionUtil.commit();

      return Response.ok(createOFBizResponseString(getClass().getName(), true, "OK")).type("application/json").build();

    } catch (IOException | RuntimeException | GenericServiceException | GenericEntityException e) {
      Debug.logError(e, getClass().getName());
      TransactionUtil.rollback(e);
      return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, e.toString())).build();
    }
  }
}

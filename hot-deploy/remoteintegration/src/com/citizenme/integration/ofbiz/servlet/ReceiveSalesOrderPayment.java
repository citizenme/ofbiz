package com.citizenme.integration.ofbiz.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
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
import org.ofbiz.order.order.OrderChangeHelper;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;
import org.ofbiz.service.ServiceUtil;

import com.citizenme.integration.ofbiz.OFBizRequest;
import com.citizenme.integration.ofbiz.helper.Config;
import com.citizenme.integration.ofbiz.helper.ConfigHelper;
import com.citizenme.integration.ofbiz.helper.RequestHelper;
import com.citizenme.integration.ofbiz.model.SalesOrderPaymentReceipt;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;

/*
 * Based on code from applications/order/src/org/ofbiz/order/OrderManagerEvents.java#receiveOfflinePayment
 * 
 */

@Path("/receivepanelsalesorderpayment")
public class ReceiveSalesOrderPayment {

  private static Config config = ConfigHelper.getConfig();
  
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
  public Response execute(InputStream requestBodyStream) throws GenericTransactionException {
    
    Map<String, Object> result = null;

    try {
      OFBizRequest ofbizRequest = RequestHelper.deserializeOFBizRequest(requestBodyStream);
      
      Set<ConstraintViolation<OFBizRequest>> constraintViolations = validator.validate(ofbizRequest);
      
      if (constraintViolations.size() > 0)
        throw new RuntimeException("Invalid input: " + constraintViolations.toString());
      
      SalesOrderPaymentReceipt paymentReceipt = (SalesOrderPaymentReceipt) ofbizRequest.getRequestParameter();

      if (TransactionUtil.begin() == false)
        throw new RuntimeException("Transaction is already unexpectedly started");

      // First, get userLogin for those calls that require it
      GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", ofbizRequest.getLogin()), true);
      
      // get the order header & payment preferences
      GenericValue orderHeader = delegator.findOne("OrderHeader", UtilMisc.toMap("orderId", paymentReceipt.getOrderId()), false);

      BigDecimal grandTotal = orderHeader.getBigDecimal("grandTotal");
      
      if (grandTotal.compareTo(paymentReceipt.getAmount()) > 0)
        throw new RuntimeException("Payment of order has to be made in full");
      
      String paymentMethodTypeId;
      
      switch (paymentReceipt.getPaymentProvider()) {
      case "PAYPAL":
        paymentMethodTypeId = "EXT_PAYPAL";
        break;
      
      default:
        throw new RuntimeException("Invalid payment provider provided");
      }

      // Update payment preference to being received
      List<GenericValue> paymentPreferences = delegator.findByAnd("OrderPaymentPreference", UtilMisc.toMap("orderId", paymentReceipt.getOrderId(), "paymentMethodTypeId", paymentMethodTypeId));

      // Should never happen, but in case of manual changes etc it's not really safe here...
      if (paymentPreferences == null || paymentPreferences.size() != 1)
        throw new RuntimeException("Order payment preferences not correctly setup");
      
      GenericValue paymentPreference = paymentPreferences.get(0);
      paymentPreference.set("statusId", "PAYMENT_RECEIVED");
      delegator.store(paymentPreference);
      
      result = dispatcher.runSync("createPaymentFromPreference", UtilMisc.toMap("userLogin", userLogin,
          "orderPaymentPreferenceId", paymentPreference.get("orderPaymentPreferenceId"), "paymentRefNum", paymentReceipt.getPaymentReference(),
          "paymentFromId", paymentReceipt.getClientAgentPartyId(), "comments", paymentReceipt.getPaymentProviderId()));

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }
      
      // Approve order 
      if (! OrderChangeHelper.approveOrder(dispatcher, userLogin, paymentReceipt.getOrderId()))
        throw new RuntimeException("approveOrder failed");

      // Create invoice for all order items
      // As it turns out this is not needed as the approveOrder calls appears to auto-generate invoice - how nice...
//      Map<String, Object> invoiceRequestMap = new HashMap<String, Object>();
//      invoiceRequestMap.put("login.username", ofbizRequest.getLogin());
//      invoiceRequestMap.put("login.password", ofbizRequest.getPassword());
//      invoiceRequestMap.put("orderId", order.getOrderId());
//
//      result = dispatcher.runSync("createInvoiceForOrderAllItems", invoiceRequestMap);
//
//      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
//        TransactionUtil.rollback();
//        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
//      }
      
      TransactionUtil.commit();
      
      return Response.ok(createOFBizResponseString(getClass().getName(), true, "OK")).type("application/json").build();

    } catch (IOException | RuntimeException | GenericEntityException | GenericServiceException e) {
      Debug.logError(e, getClass().getName());
      TransactionUtil.rollback(e);
      return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, e.toString())).build();
    }
  }
}

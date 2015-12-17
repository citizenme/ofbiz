package com.citizenme.integration.ofbiz.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
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
import org.ofbiz.base.util.UtilDateTime;
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
import com.citizenme.integration.ofbiz.helper.PaymentProviderConfig;
import com.citizenme.integration.ofbiz.helper.RequestHelper;
import com.citizenme.integration.ofbiz.model.PurchaseOrderPaymentReceipt;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;

/*
 * Based on code from applications/order/src/org/ofbiz/order/OrderManagerEvents.java#receiveOfflinePayment
 * 
 */

@Path("/sendpurchaseorderpayment")
public class SendPurchaseOrderPaymentResource {

  private static Config config;
  
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
      config = ConfigHelper.getConfig();

      OFBizRequest ofbizRequest = RequestHelper.deserializeOFBizRequest(requestBodyStream);
      
      Set<ConstraintViolation<OFBizRequest>> constraintViolations = validator.validate(ofbizRequest);
      
      if (constraintViolations.size() > 0)
        throw new RuntimeException("Invalid input: " + constraintViolations.toString());
      
      PurchaseOrderPaymentReceipt paymentReceipt = (PurchaseOrderPaymentReceipt) ofbizRequest.getRequestParameter();

      if (TransactionUtil.begin() == false)
        throw new RuntimeException("Transaction is already unexpectedly started");

      // First, get userLogin for those calls that require it
      GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", ofbizRequest.getLogin()), true);
      
      // get the order header & payment preferences
      GenericValue orderHeader = delegator.findOne("OrderHeader", UtilMisc.toMap("orderId", paymentReceipt.getOrderId()), false);

      if (orderHeader == null)
        throw new RuntimeException("Order does not exist");
      
      BigDecimal grandTotal = orderHeader.getBigDecimal("grandTotal");
      
      if (grandTotal.compareTo(paymentReceipt.getGrossAmount()) > 0)
        throw new RuntimeException("Payment of order has to be made in full");
      
      PaymentProviderConfig paymentProviderConfig = config.getPaymentProviderConfig(paymentReceipt.getPaymentProvider());
      if (paymentProviderConfig == null)
        throw new RuntimeException("Invalid payment provider provided");

      // Approve order
      if (! OrderChangeHelper.approveOrder(dispatcher, userLogin, paymentReceipt.getOrderId()))
        throw new RuntimeException("approveOrder failed");

    // Find single invoice id - or fail - it's unexpected and not safe to proceed with multiples 
    // as we expect only a single payment for the full order
      List<GenericValue> orderItemBillings = delegator.findByAnd("OrderItemBilling", UtilMisc.toMap("orderId", paymentReceipt.getOrderId()));
      Set<String> invoiceIds = new HashSet<String>();
      for (GenericValue orderItemBilling : orderItemBillings) {
        invoiceIds.add(orderItemBilling.getString("invoiceId"));
      }
  
      if (invoiceIds.size() != 1)
        throw new RuntimeException("Unexpected number of invoices for order (there should be only 1): " + invoiceIds.size());
  
      String invoiceId = (String) invoiceIds.toArray()[0];
  
      // Check payment preference status
      List<GenericValue> paymentApplications = delegator.findByAnd("PaymentApplication", UtilMisc.toMap("invoiceId", invoiceId));
  
      // Should never happen, but in case of manual changes etc it's not really safe here...
      if (paymentApplications == null || paymentApplications.size() != 1)
        throw new RuntimeException("Payment Application returns unexpected number based on invoice id (there should be exactly 1)");
      
      GenericValue paymentApplication = paymentApplications.get(0);
      
      String paymentId = (String) paymentApplication.get("paymentId");
      
      List<GenericValue> payments = delegator.findByAnd("Payment", UtilMisc.toMap("paymentId", paymentId));
      
      if (payments == null || payments.size() != 1)
        throw new RuntimeException("Payment returns unexpected number based on payment id (there should be exactly 1)");
  
      GenericValue payment = payments.get(0);
      
      Map<String, Object> finAccountTrans = UtilMisc.<String, Object>toMap(
        "userLogin", userLogin
      , "finAccountTransTypeId", "WITHDRAWAL"
      , "finAccountId", paymentProviderConfig.getFinAccountId()
      , "partyId", config.getParameter("companyPartyId")
      , "orderId", paymentReceipt.getOrderId()
      , "amount", paymentReceipt.getNetAmount()
      , "paymentId", paymentId
      , "statusId", "FINACT_TRNS_CREATED" // Needed for reconciliation
      , "comments", String.format("paymentProvider:%s/paymentProviderId:%s/paymentProviderReference:%s", paymentReceipt.getPaymentProvider(), paymentReceipt.getPaymentProviderId(), paymentReceipt.getPaymentReference())
      );

      result = dispatcher.runSync("createFinAccountTrans", finAccountTrans);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }
      
      String finAccountTransId = (String) result.get("finAccountTransId");
      
      Map<String, Object> updatePayment = UtilMisc.<String, Object>toMap(
        "userLogin", userLogin
      , "paymentId", paymentId
      , "paymentMethodTypeId", paymentProviderConfig.getPaymentMethodTypeId()
      , "paymentMethodId", paymentProviderConfig.getPaymentMethodId()
      , "finAccountTransId", finAccountTransId
      , "comments", String.format("paymentProvider:%s/paymentProviderId:%s/paymentProviderReference:%s", paymentReceipt.getPaymentProvider(), paymentReceipt.getPaymentProviderId(), paymentReceipt.getPaymentReference())
      , "effectiveDate", UtilDateTime.nowTimestamp()
      );
      
      result = dispatcher.runSync("updatePayment", updatePayment);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }

      Map<String, Object> setPaymentStatus = UtilMisc.<String, Object>toMap(
        "userLogin", userLogin
      , "paymentId", paymentId
      , "statusId", "PMNT_SENT"
      );
      
      result = dispatcher.runSync("setPaymentStatus", setPaymentStatus);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }

      Map<String, Object> createGlReconciliation = UtilMisc.<String, Object>toMap(
        "userLogin", userLogin
      , "glReconciliationName", "Reconciliation paymentId: " + paymentId
      );

      result = dispatcher.runSync("createGlReconciliation", createGlReconciliation);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }

      String glReconciliationId = (String) result.get("glReconciliationId");

      Map<String, Object> assignGlRecToFinAccTrans = UtilMisc.<String, Object>toMap(
        "userLogin", userLogin
      , "glReconciliationId", glReconciliationId
      , "finAccountTransId", finAccountTransId
      );
      
      result = dispatcher.runSync("assignGlRecToFinAccTrans", assignGlRecToFinAccTrans);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }
      
      Map<String, Object> reconcileFinAccountTrans = UtilMisc.<String, Object>toMap(
        "userLogin", userLogin
//        , "finAccountId", paymentProviderConfig.getFinAccountId()
      , "finAccountTransId", finAccountTransId
      , "organizationPartyId", config.getParameter("companyPartyId")
//        , "glReconciliationId", glReconciliationId
      );

      result = dispatcher.runSync("reconcileFinAccountTrans", reconcileFinAccountTrans);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }

      BigDecimal feeAmount = paymentReceipt.getGrossAmount().subtract(paymentReceipt.getNetAmount());

      // There's likely a fee charged by payment provider that we need to log: (gross - net) amounts returned by payment provider
      if (feeAmount.compareTo(BigDecimal.ZERO) > 0) {
        
        // Create payment provider GL transaction to offset inherent fee 
        Map<String, Object> quickCreateAcctgTransAndEntries = UtilMisc.<String, Object>toMap(
            "userLogin", userLogin
//          "finAccountTransId", "DEPOSIT"
          , "transactionDate", UtilDateTime.nowTimestamp()
          , "glFiscalTypeId", "ACTUAL"
          , "organizationPartyId", config.getParameter("companyPartyId")
          , "partyId", config.getParameter("companyPartyId")
          , "amount", feeAmount
          , "currencyUomId", paymentReceipt.getCurrency()
          , "origAmount", feeAmount
          , "origCurrencyUomId", paymentReceipt.getCurrency()
          , "acctgTransEntryTypeId", "_NA_"
          , "debitGlAccountId", paymentProviderConfig.getChargeGlDebitAccountId()
          , "creditGlAccountId", paymentProviderConfig.getChargeGlCreditAccountId()
          , "acctgTransTypeId", "EXTERNAL_ACCTG_TRANS"
          , "invoiceId", invoiceId
          , "paymentId", paymentId
          , "groupStatusId", "AES_NOT_RECONCILED"
        );

        result = dispatcher.runSync("quickCreateAcctgTransAndEntries", quickCreateAcctgTransAndEntries);
        
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
          TransactionUtil.rollback();
          return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
        }

        // Post transaction
        Map<String, Object> postAcctgTrans = UtilMisc.<String, Object>toMap(
          "acctgTransId", result.get("acctgTransId")
        , "userLogin", userLogin
        );
        
        result = dispatcher.runSync("postAcctgTrans", postAcctgTrans);
        
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
          TransactionUtil.rollback();
          return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
        }
      }
      
      TransactionUtil.commit();

      return Response.ok(createOFBizResponseString(getClass().getName(), true, "OK")).type("application/json").build();

    } catch (IOException | RuntimeException | GenericEntityException | GenericServiceException e) {
      Debug.logError(e, getClass().getName());
      TransactionUtil.rollback(e);
      return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, e.toString())).build();
    }
  }
}

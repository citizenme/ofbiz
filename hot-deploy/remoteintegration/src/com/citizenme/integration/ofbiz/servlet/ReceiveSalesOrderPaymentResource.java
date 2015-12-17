package com.citizenme.integration.ofbiz.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
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
import com.citizenme.integration.ofbiz.model.SalesOrderPaymentReceipt;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;

/*
 * Based on code from applications/order/src/org/ofbiz/order/OrderManagerEvents.java#receiveOfflinePayment
 * 
 */

@Path("/receivesalesorderpayment")
public class ReceiveSalesOrderPaymentResource {

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
      
      SalesOrderPaymentReceipt paymentReceipt = (SalesOrderPaymentReceipt) ofbizRequest.getRequestParameter();

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

      // Update payment preference to being received
      List<GenericValue> paymentPreferences = delegator.findByAnd("OrderPaymentPreference", UtilMisc.toMap("orderId", paymentReceipt.getOrderId(), "paymentMethodTypeId", paymentProviderConfig.getPaymentMethodTypeId()));

      // Should never happen, but in case of manual changes etc it's not really safe here...
      if (paymentPreferences == null || paymentPreferences.size() != 1)
        throw new RuntimeException("Order payment preferences not correctly setup");
      
      GenericValue paymentPreference = paymentPreferences.get(0);
      
      if ("PAYMENT_RECEIVED".equals(paymentPreference.getString("statusId")))
        throw new RuntimeException("Order is already paid for");
      
      paymentPreference.set("statusId", "PAYMENT_RECEIVED");
      // Was just a test
//      paymentPreference.set("maxAmount", paymentReceipt.getNetAmount()); // Override Gross to create payment from actual amount received
      delegator.store(paymentPreference);
      
      result = dispatcher.runSync("createPaymentFromPreference", UtilMisc.toMap(
        "userLogin", userLogin
      , "orderPaymentPreferenceId", paymentPreference.get("orderPaymentPreferenceId")
      , "paymentRefNum", paymentReceipt.getPaymentReference()
      , "paymentFromId", paymentReceipt.getClientAgentPartyId()
      , "comments", String.format("paymentProvider:%s/paymentProviderId:%s/paymentProviderReference:%s", paymentReceipt.getPaymentProvider(), paymentReceipt.getPaymentProviderId(), paymentReceipt.getPaymentReference())
       )
      );

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }
      
      String paymentId = (String) result.get("paymentId");

      Map<String, Object> finAccountTrans = UtilMisc.<String, Object>toMap(
        "userLogin", userLogin
      , "finAccountId", paymentProviderConfig.getFinAccountId()
      , "paymentIds", Arrays.asList( paymentId )
      , "groupInOneTransaction", "Y"
      , "paymentGroupTypeId", "BATCH_PAYMENT"
      , "paymentGroupName", "BATCH_PAYMENT paymentId: " + paymentId
      , "netAmount", paymentReceipt.getNetAmount()
      );
      
      result = dispatcher.runSync("depositWithdrawPaymentNetAmount", finAccountTrans);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }

      String finAccountTransId = (String) result.get("finAccountTransId");
      String paymentGroupId = (String) result.get("paymentGroupId");
      
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
//      , "finAccountId", paymentProviderConfig.getFinAccountId()
      , "finAccountTransId", finAccountTransId
      , "organizationPartyId", config.getParameter("companyPartyId")
//      , "glReconciliationId", glReconciliationId
      );

      result = dispatcher.runSync("reconcileFinAccountTrans", reconcileFinAccountTrans);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }

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

      // Now email the invoice
      Locale locale = new Locale((String) config.getParameter("locale"));

      Map<String, Object> bodyParameters = UtilMisc.<String, Object>toMap(
          "invoiceId", invoiceId
        , "userLogin", userLogin
        , "locale", locale);

      Map<String, Object> sendMap = UtilMisc.<String, Object>toMap(
        "sendFrom", (String) config.getParameter("invoiceEmailFrom")
      , "sendTo", paymentReceipt.getInvoiceEmail()
      , "xslfoAttachScreenLocation", "component://remoteintegration/widget/AccountingPrintScreens.xml#InvoicePDF"
      , "subject", String.format((String) config.getParameter("invoiceEmailSubject"), invoiceId, paymentReceipt.getOrderId())
      , "bodyText", (String) config.getParameter("invoiceEmailBodyText")
      , "timeZone",  TimeZone.getTimeZone((String) config.getParameter("timeZone"))
      , "locale", locale
      , "userLogin", userLogin
      , "bodyParameters", bodyParameters
      );
      
      // Don't let notification email below fail transaction - opportunistic best-effort email only ;-)
      TransactionUtil.commit();
      
      dispatcher.runAsync("sendMailFromScreen", sendMap);
      
      return Response.ok(createOFBizResponseString(getClass().getName(), true, "OK")).type("application/json").build();

    } catch (IOException | RuntimeException | GenericEntityException | GenericServiceException e) {
      Debug.logError(e, getClass().getName());
      TransactionUtil.rollback(e);
      return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, e.toString())).build();
    }
  }
}

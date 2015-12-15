package com.citizenme.integration.ofbiz.servlet;

import java.io.IOException;
import java.io.InputStream;
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
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;

import com.citizenme.integration.ofbiz.OFBizRequest;
import com.citizenme.integration.ofbiz.helper.Config;
import com.citizenme.integration.ofbiz.helper.ConfigHelper;
import com.citizenme.integration.ofbiz.helper.RequestHelper;
import com.citizenme.integration.ofbiz.model.PurchaseOrderCancellation;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;

/*
 * Based on code from applications/order/src/org/ofbiz/order/OrderManagerEvents.java#receiveOfflinePayment
 * 
 */

@Path("/cancelpurchaseorder")
public class CancelPurchaseOrderResource {

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
    
    // Map<String, Object> result = null;

    try {
      config = ConfigHelper.getConfig();
      
      OFBizRequest ofbizRequest = RequestHelper.deserializeOFBizRequest(requestBodyStream);
      
      Set<ConstraintViolation<OFBizRequest>> constraintViolations = validator.validate(ofbizRequest);
      
      if (constraintViolations.size() > 0)
        throw new RuntimeException("Invalid input: " + constraintViolations.toString());
      
      PurchaseOrderCancellation orderCancellation = (PurchaseOrderCancellation) ofbizRequest.getRequestParameter();

      if (TransactionUtil.begin() == false)
        throw new RuntimeException("Transaction is already unexpectedly started");

      // First, get userLogin for those calls that require it
      GenericValue userLogin = delegator.findOne("UserLogin", UtilMisc.toMap("userLoginId", ofbizRequest.getLogin()), true);
      
      // get the order header & payment preferences
      GenericValue orderHeader = delegator.findOne("OrderHeader", UtilMisc.toMap("orderId", orderCancellation.getOrderId()), false);

      if (orderHeader == null)
        throw new RuntimeException("Order does not exist");
      
      switch (orderHeader.getString("statusId")) {
      
      case "ORDER_CREATED":
      case "ORDER_APPROVED":
        break;
       
      case "ORDER_CANCELLED":
        throw new RuntimeException("Can't cancel an already cancelled order");
      case "ORDER_COMPLETED":
        throw new RuntimeException("Can't cancel an already completed order");
      default:
        throw new RuntimeException("Abort-abort - unknown order status");
      }
      
      // Cancel order 
      if (! OrderChangeHelper.cancelOrder(dispatcher, userLogin, orderCancellation.getOrderId()))
        throw new RuntimeException("cancelOrder failed");

      // Now email the order cancellation
//      Locale locale = new Locale((String) config.getParameter("locale"));

//      Map<String, Object> bodyParameters = UtilMisc.<String, Object>toMap(
//          "orderId", orderCancellation.getOrderId()
//        , "userLogin", userLogin
//        , "locale", locale);
//
//      Map<String, Object> sendMap = UtilMisc.<String, Object>toMap(
//        "sendFrom", (String) config.getParameter("invoiceEmailFrom")
//      , "sendTo", orderCancellation.getNotificaitonEmail()
////      , "xslfoAttachScreenLocation", "component://remoteintegration/widget/AccountingPrintScreens.xml#InvoicePDF"
//      , "subject", String.format((String) config.getParameter("orderCancellationEmailSubject"), orderCancellation.getOrderId())
//      , "bodyText", (String) config.getParameter("orderCancellationEmailBodyText")
//      , "timeZone",  TimeZone.getTimeZone((String) config.getParameter("timeZone"))
//      , "locale", locale
//      , "userLogin", userLogin
//      , "bodyParameters", bodyParameters
//      );
      
      // Don't let notification email below fail transaction - opportunistic best-effort email only ;-)
      TransactionUtil.commit();

//      dispatcher.runAsync("sendMailFromScreen", sendMap);

      return Response.ok(createOFBizResponseString(getClass().getName(), true, "OK")).type("application/json").build();

    } catch (IOException | RuntimeException | GenericEntityException e) {
      Debug.logError(e, getClass().getName());
      TransactionUtil.rollback(e);
      return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, e.toString())).build();
    }
  }
}

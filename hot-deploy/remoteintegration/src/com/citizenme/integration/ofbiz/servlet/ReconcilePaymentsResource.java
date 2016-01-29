package com.citizenme.integration.ofbiz.servlet;

import com.citizenme.integration.ofbiz.OFBizRequest;
import com.citizenme.integration.ofbiz.helper.Config;
import com.citizenme.integration.ofbiz.helper.ConfigHelper;
import com.citizenme.integration.ofbiz.helper.RequestHelper;
import com.citizenme.integration.ofbiz.model.PaymentReconciliation;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.transaction.GenericTransactionException;
import org.ofbiz.entity.transaction.TransactionUtil;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.createOFBizResponseString;

@Path("/reconcilepayments")
public class ReconcilePaymentsResource {

  private static Config config;

  private static Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  private GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

  @POST
  @Consumes("application/json")
  @Produces("application/json")
  public Response execute(InputStream requestBodyStream) throws GenericTransactionException {
    
    try {
      config = ConfigHelper.getConfig();
      OFBizRequest ofbizRequest = RequestHelper.deserializeOFBizRequest(requestBodyStream);
      Set<ConstraintViolation<OFBizRequest>> constraintViolations = validator.validate(ofbizRequest);
      
      if (constraintViolations.size() > 0)
        throw new RuntimeException("Invalid input: " + constraintViolations.toString());
      
      PaymentReconciliation pr = (PaymentReconciliation) ofbizRequest.getRequestParameter();

      if (!TransactionUtil.begin())
        throw new RuntimeException("Transaction is already unexpectedly started");

      if (pr.getPaymentReferenceIds().length == 0) {
        throw new RuntimeException("Empty array of payment IDs received");
      }

      // todo repeat for all payment IDs
      String paymentId = pr.getPaymentReferenceIds()[0];
      List<GenericValue> payments = delegator.findByAnd("Payment", UtilMisc.toMap("paymentId", paymentId));

      if (payments == null || payments.size() != 1) {
        throw new RuntimeException("Payment returns unexpected number based on payment id (there should be exactly 1)");
      } else {
        // payment actually exists!
        // GenericValue payment = payments.get(0);
        // todo it would be nice to check the amounts etc... for now, let's return OK if payment with same ID exists
        return Response.ok(createOFBizResponseString(getClass().getName(), true, "OK")).type("application/json").build();
      }
    } catch (IOException | RuntimeException | GenericEntityException e) {
      Debug.logError(e, getClass().getName());
      TransactionUtil.rollback(e);
      return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, e.toString())).build();
    }
  }
}

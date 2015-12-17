package com.citizenme.integration.ofbiz.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
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
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;
import org.ofbiz.service.ServiceUtil;

import com.citizenme.integration.ofbiz.OFBizRequest;
import com.citizenme.integration.ofbiz.helper.Config;
import com.citizenme.integration.ofbiz.helper.ConfigHelper;
import com.citizenme.integration.ofbiz.helper.ContactMechHelper;
import com.citizenme.integration.ofbiz.helper.RequestHelper;
import com.citizenme.integration.ofbiz.model.Citizen;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;


@Path("/createorupdatecitizen")
public class CreateOrUpdateCitizenResource {

  private static Config config;

  // Product may vary later - but hardcoded for now
  private static String suppliedProduct = "PANEL-ENTRY";
  
  private static Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  
  private GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
  private LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default",delegator);
  
  @POST
  @Consumes("application/json")
  @Produces("application/json")
  //@Path("{partyId}")
  //@PathParam("partyId") String partyId,
  public Response execute(InputStream requestBodyStream) throws GenericTransactionException {
    
    try {
      config = ConfigHelper.getConfig();

      OFBizRequest ofbizRequest = RequestHelper.deserializeOFBizRequest(requestBodyStream);
      
      Set<ConstraintViolation<OFBizRequest>> constraintViolations = validator.validate(ofbizRequest);
      
      if (constraintViolations.size() > 0)
        throw new RuntimeException("Invalid input: " + constraintViolations.toString());
      
      Citizen citizen = (Citizen) ofbizRequest.getRequestParameter();
      
      Map<String, Object> citizenRequestMap = new HashMap<String, Object>();

      String serviceName = null;

      Map<String, Object> result = null;

      if (TransactionUtil.begin() == false)
        throw new RuntimeException("Transaction is already unexpectedly started");
      
      GenericValue partyObject = delegator.findOne("Party", UtilMisc.toMap("partyId", citizen.getPartyId()), false);

      GenericValue partyNameViewObject = delegator.findOne("PartyNameView", UtilMisc.toMap("partyId", citizen.getPartyId()), false);
      if (partyNameViewObject == null) {
        serviceName = "createPerson";

        citizenRequestMap.put("firstName", citizen.getFirstName());
        citizenRequestMap.put("lastName", citizen.getLastName());
        citizenRequestMap.put("description", citizen.getDescription());
        citizenRequestMap.put("preferredCurrencyUomId", citizen.getPreferredCurrencyUomId());
        
      } else {
        serviceName = "updatePerson";

        // Constraint: update always must have these
        citizenRequestMap.put("firstName", citizen.getFirstName());
        citizenRequestMap.put("lastName", citizen.getLastName());

        String description = (String) partyNameViewObject.get("description");
        if (citizen.getDescription() != null && ! citizen.getDescription().equals(description))
          citizenRequestMap.put("description", citizen.getDescription());
          
        String preferredCurrencyUomId = (String) partyObject.get("preferredCurrencyUomId");
        if (! citizen.getPreferredCurrencyUomId().equals(preferredCurrencyUomId))
          citizenRequestMap.put("preferredCurrencyUomId", citizen.getPreferredCurrencyUomId());
      }
  
      if (citizenRequestMap.size() > 2) {
        citizenRequestMap.put("partyId", citizen.getPartyId());
        citizenRequestMap.put("login.username", ofbizRequest.getLogin());
        citizenRequestMap.put("login.password", ofbizRequest.getPassword());
        
        result = dispatcher.runSync(serviceName, citizenRequestMap);

        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
          TransactionUtil.rollback();
          return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
        }
      }

      // Add Contact Mechanism EMAIL_ADDRESS to client agent
      result = ContactMechHelper.findOrCreatePartyContactMechEmailAddress (ofbizRequest.getLogin(), ofbizRequest.getPassword(), citizen.getPartyId(), citizen.getEmail(), "PRIMARY_EMAIL", dispatcher);
      
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }
      
      // Set-up citizen with SUPPLIER relationship
      GenericValue partyToRole = delegator.findOne("PartyRole", UtilMisc.toMap("partyId", citizen.getPartyId(), "roleTypeId", "SUPPLIER"), false);
      if (partyToRole == null) {
        // Add party role SUPPLIER as person will supply a product (service)
        Map<String, Object> createPartyRoleMap = new HashMap<String, Object>();
        createPartyRoleMap.put("login.username", ofbizRequest.getLogin());
        createPartyRoleMap.put("login.password", ofbizRequest.getPassword());
        createPartyRoleMap.put("partyId", citizen.getPartyId());
        createPartyRoleMap.put("roleTypeId", "SUPPLIER");

        result = dispatcher.runSync("createPartyRole", createPartyRoleMap);
  
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
          TransactionUtil.rollback();
          return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
        }
      }

      // Set-up citizen as a supplier of PANEL-ENTRY product
      List<GenericValue> supplierProducts = delegator.findByAnd("SupplierProduct", UtilMisc.toMap("partyId", citizen.getPartyId(), "productId", suppliedProduct));
      if (supplierProducts.isEmpty()) {
        // Add party as supplier of product
        Map<String, Object> createSupplierProduct = new HashMap<String, Object>();
        createSupplierProduct.put("login.username", ofbizRequest.getLogin());
        createSupplierProduct.put("login.password", ofbizRequest.getPassword());
        createSupplierProduct.put("partyId", citizen.getPartyId());
        createSupplierProduct.put("productId", suppliedProduct);
        createSupplierProduct.put("supplierProductId", suppliedProduct); // Just keep the same as our product id
        createSupplierProduct.put("currencyUomId", citizen.getPreferredCurrencyUomId());
        createSupplierProduct.put("minimumOrderQuantity", BigDecimal.ZERO);
        createSupplierProduct.put("availableFromDate", UtilDateTime.nowTimestamp());
        createSupplierProduct.put("lastPrice", BigDecimal.ZERO);
        
        result = dispatcher.runSync("createSupplierProduct", createSupplierProduct);
  
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
          TransactionUtil.rollback();
          return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
        }
      }
      
      TransactionUtil.commit();
      
      return Response.ok(createOFBizResponseString(getClass().getName(), true, "OK")).type("application/json").build();

    } catch (GenericEntityException | IOException | GenericServiceException | RuntimeException e) {
      Debug.logError(e, getClass().getName());
      TransactionUtil.rollback(e);
      return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, e.toString())).build();
    }
  }
}

package com.citizenme.integration.ofbiz.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
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
import com.citizenme.integration.ofbiz.helper.ContactMechHelper;
import com.citizenme.integration.ofbiz.helper.RequestHelper;
import com.citizenme.integration.ofbiz.model.ClientAgent;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;


@Path("/createorupdateclientagent")
public class CreateOrUpdateClientAgentResource {

  private static Config config;

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
      
      ClientAgent agent = (ClientAgent) ofbizRequest.getRequestParameter();
      
      Map<String, Object> agentRequestMap = new HashMap<String, Object>();

      String serviceName = null;

      Map<String, Object> result = null;

      if (TransactionUtil.begin() == false)
        throw new RuntimeException("Transaction is already unexpectedly started");
      
      GenericValue partyObject = delegator.findOne("Party", UtilMisc.toMap("partyId", agent.getPartyId()), false);

      GenericValue partyNameViewObject = delegator.findOne("PartyNameView", UtilMisc.toMap("partyId", agent.getPartyId()), false);
      if (partyNameViewObject == null) {
        serviceName = "createPerson";

        agentRequestMap.put("firstName", agent.getFirstName());
        agentRequestMap.put("lastName", agent.getLastName());
        agentRequestMap.put("description", agent.getDescription());
        agentRequestMap.put("preferredCurrencyUomId", agent.getPreferredCurrencyUomId());
        
      } else {
        serviceName = "updatePerson";

        // Constraint: update always must have these
        agentRequestMap.put("firstName", agent.getFirstName());
        agentRequestMap.put("lastName", agent.getLastName());

        String description = (String) partyNameViewObject.get("description");
        if (agent.getDescription() != null && ! agent.getDescription().equals(description))
          agentRequestMap.put("description", agent.getDescription());
          
        String preferredCurrencyUomId = (String) partyObject.get("preferredCurrencyUomId");
        if (! agent.getPreferredCurrencyUomId().equals(preferredCurrencyUomId))
          agentRequestMap.put("preferredCurrencyUomId", agent.getPreferredCurrencyUomId());
      }
  
      if (agentRequestMap.size() > 2) {
        agentRequestMap.put("partyId", agent.getPartyId());
        agentRequestMap.put("login.username", ofbizRequest.getLogin());
        agentRequestMap.put("login.password", ofbizRequest.getPassword());
        
        result = dispatcher.runSync(serviceName, agentRequestMap);

        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
          TransactionUtil.rollback();
          return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
        }
      }

      // Create relationship from client agent to client organisation
      Map<String, Object> relationshipRequestMap = new HashMap<String, Object>();
      relationshipRequestMap.put("login.username", ofbizRequest.getLogin());
      relationshipRequestMap.put("login.password", ofbizRequest.getPassword());
      relationshipRequestMap.put("partyId", ofbizRequest.getLogin());
      relationshipRequestMap.put("partyIdFrom", agent.getPartyId());
      relationshipRequestMap.put("partyIdTo", agent.getClientOrganisationPartyId());
      relationshipRequestMap.put("partyRelationshipTypeId", agent.getClientOrganisationRelationshipType());
      relationshipRequestMap.put("roleTypeIdFrom", "ACCOUNT"); 
      relationshipRequestMap.put("roleTypeIdTo", "CONTACT");

      result = dispatcher.runSync("createUpdatePartyRelationshipAndRoles", relationshipRequestMap);
      
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }

      // Add Contact Mechanism EMAIL_ADDRESS to client agent
      result = ContactMechHelper.findOrCreatePartyContactMechEmailAddress (ofbizRequest.getLogin(), ofbizRequest.getPassword(), agent.getPartyId(), agent.getEmail(), "PRIMARY_EMAIL", dispatcher);
      
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }
      
      // Check if party is already set-up with relationship CUSTOMER
      GenericValue partyToRole = delegator.findOne("PartyRole", UtilMisc.toMap("partyId", agent.getPartyId(), "roleTypeId", "CUSTOMER"), false);
      if (partyToRole == null) {
        // Add party role CUSTOMER
        Map<String, Object> createPartyRoleMap = new HashMap<String, Object>();
        createPartyRoleMap.put("login.username", ofbizRequest.getLogin());
        createPartyRoleMap.put("login.password", ofbizRequest.getPassword());
        createPartyRoleMap.put("partyId", agent.getPartyId());
        createPartyRoleMap.put("roleTypeId", "CUSTOMER");

        result = dispatcher.runSync("createPartyRole", createPartyRoleMap);
  
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

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
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;
import org.ofbiz.service.ServiceUtil;

import com.citizenme.integration.ofbiz.OFBizRequest;
import com.citizenme.integration.ofbiz.helper.RequestHelper;
import com.citizenme.integration.ofbiz.model.ClientOrganisation;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;


@Path("/createorupdateclientorganisation")
public class CreateOrUpdateClientOrganisationResource {

  private static Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  
  private GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
  private LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default",delegator);
  
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
      
      ClientOrganisation organisation = (ClientOrganisation) ofbizRequest.getRequestParameter();
      
      Map<String, Object> requestMap = new HashMap<String, Object>();

      String serviceName = null;
      
      Map<String, Object> result = null;
      
      GenericValue partyObject = delegator.findOne("PartyNameView", UtilMisc.toMap("partyId", organisation.getPartyId()), false);
      if (partyObject == null) {
        serviceName = "createPartyGroup";
        
        requestMap.put("groupName", organisation.getGroupName());
        requestMap.put("partyId", organisation.getPartyId());
        
      } else {
        serviceName = "updatePartyGroup";
        
        String groupName = (String) partyObject.get("groupName");
        
        if (! organisation.getGroupName().equals(groupName)) {
          requestMap.put("groupName", organisation.getGroupName());
        }
        
      }
  
      if (requestMap.size() > 0) {
        requestMap.put("partyId", organisation.getPartyId());
        requestMap.put("login.username", ofbizRequest.getLogin());
        requestMap.put("login.password", ofbizRequest.getPassword());

        result = dispatcher.runSync(serviceName, requestMap);

        if (ServiceUtil.isSuccess(result)) {
          return Response.ok(createResponse(getClass().getName(), true, serviceName)).type("application/json").build();
        }
        
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
          return Response.serverError().entity(createResponse(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
        }
      }
  
      return Response.ok(createResponse(getClass().getName(), true, "No update needed")).type("application/json").build();

    } catch (GenericEntityException | IOException | GenericServiceException | RuntimeException e) {
      Debug.logError(e, getClass().getName());
      return Response.serverError().entity(createResponse(getClass().getName(), false, e.toString())).build();
    }
  }
}

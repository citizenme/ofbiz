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
import com.citizenme.integration.ofbiz.helper.TaxAuthority;
import com.citizenme.integration.ofbiz.model.ClientOrganisation;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.*;


@Path("/createorupdateclientorganisation")
public class CreateOrUpdateClientOrganisationResource {

  private static Config config = ConfigHelper.getConfig();

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
      OFBizRequest ofbizRequest = RequestHelper.deserializeOFBizRequest(requestBodyStream);
      
      Set<ConstraintViolation<OFBizRequest>> constraintViolations = validator.validate(ofbizRequest);
      
      if (constraintViolations.size() > 0)
        throw new RuntimeException("Invalid input: " + constraintViolations.toString());
      
      ClientOrganisation organisation = (ClientOrganisation) ofbizRequest.getRequestParameter();

      String countryGeoId = organisation.getTaxAuthorityLocation();

      TaxAuthority taxAuthority = config.getTaxAuthorities().get(countryGeoId);

      Map<String, Object> organisationRequestMap = new HashMap<String, Object>();

      String serviceName = null;
      
      Map<String, Object> result = null;
      
      if (TransactionUtil.begin() == false)
        throw new RuntimeException("Transaction is already unexpectedly started");

      GenericValue partyObject = delegator.findOne("PartyNameView", UtilMisc.toMap("partyId", organisation.getPartyId()), false);
      if (partyObject == null) {
        serviceName = "createPartyGroup";
        organisationRequestMap.put("groupName", organisation.getGroupName());
      } else {
        serviceName = "updatePartyGroup";
        
        String groupName = (String) partyObject.get("groupName");
        
        if (! organisation.getGroupName().equals(groupName)) {
          organisationRequestMap.put("groupName", organisation.getGroupName());
        }
      }
  
      if (organisationRequestMap.size() > 0) {
        organisationRequestMap.put("partyId", organisation.getPartyId());
        organisationRequestMap.put("login.username", ofbizRequest.getLogin());
        organisationRequestMap.put("login.password", ofbizRequest.getPassword());

        result = dispatcher.runSync(serviceName, organisationRequestMap);

        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
          TransactionUtil.rollback();
          return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
        }
      }
  
      result = ContactMechHelper.findOrCreatePartyContactMechEmailAddress (ofbizRequest.getLogin(), ofbizRequest.getPassword(), organisation.getPartyId(), organisation.getEmail(), "PRIMARY_EMAIL", dispatcher);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        TransactionUtil.rollback();
        return Response.serverError().entity(createOFBizResponseString(getClass().getName(), false, ServiceUtil.getErrorMessage(result))).type("application/json").build();
      }
      
      GenericValue partyTaxAuthInfo = delegator.findOne(
        "PartyTaxAuthInfo"
      , UtilMisc.toMap(
        "partyId", organisation.getPartyId()
      , "taxAuthGeoId", taxAuthority.getGeoId()
      , "taxAuthPartyId", taxAuthority.getPartyId()
      , "fromDate", UtilDateTime.toTimestamp(8, 1, 2015, 0, 0, 0) // TODO: For now, just hard code from date for easy perusal - fix later
        )
      , false);
      if (partyTaxAuthInfo == null) {

        Map<String, Object> partyTaxAuthInfoRequestMap = new HashMap<String, Object>();
        partyTaxAuthInfoRequestMap.put("login.username", ofbizRequest.getLogin());
        partyTaxAuthInfoRequestMap.put("login.password", ofbizRequest.getPassword());
        partyTaxAuthInfoRequestMap.put("partyId", organisation.getPartyId());
        partyTaxAuthInfoRequestMap.put("taxAuthGeoId", taxAuthority.getGeoId());
        partyTaxAuthInfoRequestMap.put("taxAuthPartyId", taxAuthority.getPartyId());
        partyTaxAuthInfoRequestMap.put("fromDate", UtilDateTime.toTimestamp(8, 1, 2015, 0, 0, 0));
        partyTaxAuthInfoRequestMap.put("partyTaxId", organisation.getVatNumber());
        partyTaxAuthInfoRequestMap.put("isExempt", organisation.isVatExempt() ? "Y" : "N");
        
        result = dispatcher.runSync("createPartyTaxAuthInfo", partyTaxAuthInfoRequestMap);

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

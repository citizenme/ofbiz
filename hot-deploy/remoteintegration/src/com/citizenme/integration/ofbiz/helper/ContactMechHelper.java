package com.citizenme.integration.ofbiz.helper;

import static com.citizenme.integration.ofbiz.helper.RequestHelper.createResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ofbiz.entity.GenericEntity;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

public class ContactMechHelper {

  public static String findOrCreatePartyContactMechEmail (String login, String password, String partyId, String email, LocalDispatcher dispatcher) throws GenericServiceException {
    
    Map<String, Object> result = null;

    Map<String, Object> checkContactMechRequestMap = new HashMap<String, Object>();
    checkContactMechRequestMap.put("login.username", login);
    checkContactMechRequestMap.put("login.password", password);
    checkContactMechRequestMap.put("partyId", partyId);
    checkContactMechRequestMap.put("showOld", false);
    checkContactMechRequestMap.put("contactMechTypeId", "EMAIL_ADDRESS");

    // Check if email address exists and is linked to party
    result = dispatcher.runSync("getPartyContactMechValueMaps", checkContactMechRequestMap);

    if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
      return createResponse(ContactMechHelper.class.getName(), false, ServiceUtil.getErrorMessage(result));
    }

    List<GenericEntity> partyContactMechValueMaps = (List<GenericEntity>) result.get("valueMaps");
    // Debug.log("partyContactMechs: " + RequestHelper.toJson(partyContactMechValueMaps));
    boolean partyContactMechExists = false;
    for (Map<String, Object> partyContactMechValueMap : partyContactMechValueMaps) {

      Map<String, Object> contactMech = (Map<String, Object>) partyContactMechValueMap.get("contactMech");
      Map<String, Object> contactMechType = (Map<String, Object>) partyContactMechValueMap.get("contactMechType");
      Map<String, Object> partyContactMech = (Map<String, Object>) partyContactMechValueMap.get("partyContactMech");
      // Not sure what this might contain as it's an empty list/array
      List<?> partyContactMechPurposes = (List<?>) partyContactMechValueMap.get("partyContactMechPurposes");

      if (contactMech != null && email.equals(contactMech.get("infoString"))) {
        partyContactMechExists = true;
      }
    }

    // Create
    if ( ! partyContactMechExists ) {
      Map<String, Object> contactMechRequestMap = new HashMap<String, Object>();
      contactMechRequestMap.put("login.username", login);
      contactMechRequestMap.put("login.password", password);
      contactMechRequestMap.put("emailAddress", email);

      result = dispatcher.runSync("createEmailAddress", contactMechRequestMap);
    
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        return createResponse(ContactMechHelper.class.getName(), false, ServiceUtil.getErrorMessage(result));
      }

      String contactMechId = (String) result.get("contactMechId");
      
      Map<String, Object> partyContactMechRequestMap = new HashMap<String, Object>();
      partyContactMechRequestMap.put("login.username", login);
      partyContactMechRequestMap.put("login.password", password);
      partyContactMechRequestMap.put("partyId", partyId);
      partyContactMechRequestMap.put("contactMechId", contactMechId);
      partyContactMechRequestMap.put("allowSolicitation", "N");
      
      Map<String, Object> serviceResults = dispatcher.runSync("createPartyContactMech", partyContactMechRequestMap);
      
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        return createResponse(ContactMechHelper.class.getName(), false, ServiceUtil.getErrorMessage(result));
      }
    }

    return null;
  }
  
}

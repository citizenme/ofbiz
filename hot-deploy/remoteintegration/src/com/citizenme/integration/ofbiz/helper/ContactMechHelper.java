package com.citizenme.integration.ofbiz.helper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ofbiz.entity.GenericEntity;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.citizenme.integration.ofbiz.model.PostalAddress;

public class ContactMechHelper {

  public static Map<String, Object> findOrCreatePartyContactMechEmailAddress (String login, String password, String partyId, String email, String purposeTypeId, LocalDispatcher dispatcher) throws GenericServiceException {
    
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
      return result;
    }

    List<GenericEntity> partyContactMechValueMaps = (List<GenericEntity>) result.get("valueMaps");
    // Debug.log("partyContactMechs: " + RequestHelper.toJson(partyContactMechValueMaps));
    boolean partyContactMechExists = false;
    boolean partyContactMechPurposeExists = false;
    
    String contactMechId = null;
    
    for (Map<String, Object> partyContactMechValueMap : partyContactMechValueMaps) {

      Map<String, Object> contactMech = (Map<String, Object>) partyContactMechValueMap.get("contactMech");
      Map<String, Object> contactMechType = (Map<String, Object>) partyContactMechValueMap.get("contactMechType");
      Map<String, Object> partyContactMech = (Map<String, Object>) partyContactMechValueMap.get("partyContactMech");
      // Not sure what this might contain as it's an empty list/array
      List<GenericEntity> partyContactMechPurposes = (List<GenericEntity>) partyContactMechValueMap.get("partyContactMechPurposes");

      if (contactMech != null && email.equals(contactMech.get("infoString"))) {
        partyContactMechExists = true;
        contactMechId = (String) contactMech.get("contactMechId");

        for (GenericEntity gv : partyContactMechPurposes) {
          if (gv.containsKey("contactMechPurposeTypeId") && purposeTypeId.equals(gv.get("contactMechPurposeTypeId")))
            partyContactMechPurposeExists = true;
        }
      }
    }

    // Create party contact mechanism
    if ( ! partyContactMechExists ) {
      Map<String, Object> contactMechRequestMap = new HashMap<String, Object>();
      contactMechRequestMap.put("login.username", login);
      contactMechRequestMap.put("login.password", password);
      contactMechRequestMap.put("emailAddress", email);

      result = dispatcher.runSync("createEmailAddress", contactMechRequestMap);
    
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        return result;
      }

      contactMechId = (String) result.get("contactMechId");
      
      Map<String, Object> partyContactMechRequestMap = new HashMap<String, Object>();
      partyContactMechRequestMap.put("login.username", login);
      partyContactMechRequestMap.put("login.password", password);
      partyContactMechRequestMap.put("partyId", partyId);
      partyContactMechRequestMap.put("contactMechId", contactMechId);
      partyContactMechRequestMap.put("allowSolicitation", "N");
      
      result = dispatcher.runSync("createPartyContactMech", partyContactMechRequestMap);
      
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        return result;
      }
    }

    // Create party contact mechanism purpose
    if (! partyContactMechPurposeExists) {
      Map<String, Object> contactMechPurposeRequestMap = new HashMap<String, Object>();
      contactMechPurposeRequestMap.put("login.username", login);
      contactMechPurposeRequestMap.put("login.password", password);
      contactMechPurposeRequestMap.put("partyId", partyId);
      contactMechPurposeRequestMap.put("contactMechId", contactMechId);
      contactMechPurposeRequestMap.put("contactMechPurposeTypeId", purposeTypeId);
      
      result = dispatcher.runSync("createPartyContactMechPurpose", contactMechPurposeRequestMap);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        return result;
      }
    }
    
    Map<String, Object> response = ServiceUtil.returnSuccess();
    response.put("contactMechId", contactMechId);
    
    return response;
  }


  /*
   * 
   */
  public static Map<String, Object> findOrCreatePartyContactMechPostalAddress (String login, String password, String partyId, PostalAddress postalAddress, String purposeTypeId, LocalDispatcher dispatcher) throws GenericServiceException {

    Map<String, Object> result = null;

    Map<String, Object> checkContactMechRequestMap = new HashMap<String, Object>();
    checkContactMechRequestMap.put("login.username", login);
    checkContactMechRequestMap.put("login.password", password);
    checkContactMechRequestMap.put("partyId", partyId);
    checkContactMechRequestMap.put("showOld", false);
    checkContactMechRequestMap.put("contactMechTypeId", "POSTAL_ADDRESS");
   
    // Check if email address exists and is linked to party
    result = dispatcher.runSync("getPartyContactMechValueMaps", checkContactMechRequestMap);

    if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
      return result;
    }

    List<GenericEntity> partyContactMechValueMaps = (List<GenericEntity>) result.get("valueMaps");
    // Debug.log("partyContactMechs: " + RequestHelper.toJson(partyContactMechValueMaps));
    boolean partyContactMechExists = false;
    boolean partyContactMechPurposeExists = false;
    
    String contactMechId = null;
    
    for (Map<String, Object> partyContactMechValueMap : partyContactMechValueMaps) {

      Map<String, Object> contactMech = (Map<String, Object>) partyContactMechValueMap.get("contactMech");
      Map<String, Object> contactMechType = (Map<String, Object>) partyContactMechValueMap.get("contactMechType");
      Map<String, Object> partyContactMech = (Map<String, Object>) partyContactMechValueMap.get("partyContactMech");
      Map<String, Object> postalAddressContactMech = (Map<String, Object>) partyContactMechValueMap.get("postalAddress");
      
      // Not sure what this might contain as it's an empty list/array
      List<GenericEntity> partyContactMechPurposes = (List<GenericEntity>) partyContactMechValueMap.get("partyContactMechPurposes");

      PostalAddress existingAddress = PostalAddress.create(
        (String) postalAddressContactMech.get("toName")
      , (String) postalAddressContactMech.get("attnName")
      , (String) postalAddressContactMech.get("address1")
      , (String) postalAddressContactMech.get("address2")
      , (String) postalAddressContactMech.get("city")
      , (String) postalAddressContactMech.get("postalCode")
      , (String) postalAddressContactMech.get("countryGeoId")
      );
      
      if (contactMech != null && postalAddress.equals(existingAddress)) {
        partyContactMechExists = true;
        contactMechId = (String) contactMech.get("contactMechId");

        for (GenericEntity gv : partyContactMechPurposes) {
          if (gv.containsKey("contactMechPurposeTypeId") && purposeTypeId.equals(gv.get("contactMechPurposeTypeId")))
            partyContactMechPurposeExists = true;
        }
      }
    }

    // Create postal address & party contact mechanism
    if ( ! partyContactMechExists ) {
      Map<String, Object> contactMechRequestMap = new HashMap<String, Object>();
      contactMechRequestMap.put("login.username", login);
      contactMechRequestMap.put("login.password", password);

      contactMechRequestMap.put("toName", postalAddress.getToName());
      contactMechRequestMap.put("attnName", postalAddress.getAttnName());
      contactMechRequestMap.put("address1", postalAddress.getAddress1());
      contactMechRequestMap.put("address2", postalAddress.getAddress2());
      contactMechRequestMap.put("city", postalAddress.getCity());
      contactMechRequestMap.put("postalCode", postalAddress.getPostalCode());
      contactMechRequestMap.put("countryGeoId", postalAddress.getCountryGeoId());
      
      result = dispatcher.runSync("createPostalAddress", contactMechRequestMap);
    
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        return result;
      }

      contactMechId = (String) result.get("contactMechId");
      
      Map<String, Object> partyContactMechRequestMap = new HashMap<String, Object>();
      partyContactMechRequestMap.put("login.username", login);
      partyContactMechRequestMap.put("login.password", password);
      partyContactMechRequestMap.put("partyId", partyId);
      partyContactMechRequestMap.put("contactMechId", contactMechId);
      partyContactMechRequestMap.put("allowSolicitation", "N");
      
      result = dispatcher.runSync("createPartyContactMech", partyContactMechRequestMap);
      
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        return result;
      }
    }

    // Create party contact mechanism purpose
    if (! partyContactMechPurposeExists) {
      Map<String, Object> contactMechPurposeRequestMap = new HashMap<String, Object>();
      contactMechPurposeRequestMap.put("login.username", login);
      contactMechPurposeRequestMap.put("login.password", password);
      contactMechPurposeRequestMap.put("partyId", partyId);
      contactMechPurposeRequestMap.put("contactMechId", contactMechId);
      contactMechPurposeRequestMap.put("contactMechPurposeTypeId", purposeTypeId);
      
      result = dispatcher.runSync("createPartyContactMechPurpose", contactMechPurposeRequestMap);

      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        return result;
      }
    }
    
    Map<String, Object> response = ServiceUtil.returnSuccess();
    response.put("contactMechId", contactMechId);
    
    return response;
  }

}

package com.citizenme.integration.ofbiz.helper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

import org.ofbiz.base.util.Debug;

import com.citizenme.integration.ofbiz.OFBizRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class RequestHelper {

  private static ObjectMapper om = new ObjectMapper();
  
  static {
    // Send
    om.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    om.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    // Receive
    om.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    om.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
  }

  public static OFBizRequest deserializeOFBizRequest(InputStream is) throws IOException {
    return om.readValue(is, OFBizRequest.class);
  }
  
  public static Map<String, Object> createResponseMap(String operation, boolean status, String message) {

    Map<String, Object> response = new TreeMap<String, Object>();

    response.put("operation", operation);
    response.put("status", status);
    response.put("message", message);

    return response;
  }

  public static String createResponse(String operation, boolean status, String message) {
    
    try {
      return om.writeValueAsString(createResponseMap(operation, status, message));
    } catch (JsonProcessingException e) {
      Debug.log(e);
      return "";
    }
  }

  public static String createResponse(String operation, boolean status, String message, String requestId) {
    
    try {
      Map<String, Object> response = createResponseMap(operation, status, message);
      response.put("X-Request-Id", requestId);
      return om.writeValueAsString(response);
    
    } catch (JsonProcessingException e) {
      // TODO Auto-generated catch block
      Debug.log(e);
      return "";
    }
  }
  
  public static String createResponse(String operation, boolean status, String message, String requestId, Object values) {
    
    try {
      Map<String, Object> response = createResponseMap(operation, status, message);
      response.put("X-Request-Id", requestId);
      response.put("values", values);
      return om.writeValueAsString(response);
    } catch (JsonProcessingException e) {
      Debug.log(e);
      return "";
    }
  }
  
  public static String toJson(Object o) {
    
    try {
      return om.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      return "";
    }
  }
  
}

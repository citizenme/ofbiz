package com.citizenme.integration.ofbiz.helper;

import java.io.File;
import java.io.IOException;

import org.ofbiz.base.util.FileUtil;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class ConfigHelper {

  private static ObjectMapper om = new ObjectMapper();

  private static Config config = null;

  static {
    // Send
    om.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    om.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    // Receive
    om.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    om.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
  }
  
  public static synchronized Config getConfig() {
    
    if (config != null)
      return config;
    
    try {
      String configJson = FileUtil.readString("UTF-8", new File("hot-deploy/remoteintegration/config/config.json"));
      
      config = om.readValue(configJson, Config.class);
      
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    
    return config;
  }
  
}

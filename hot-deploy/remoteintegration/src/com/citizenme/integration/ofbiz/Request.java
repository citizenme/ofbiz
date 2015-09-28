package com.citizenme.integration.ofbiz;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class Request implements Serializable {

  /**
   * 
   */
  private static final long serialVersionUID = 3716694558837947495L;

  private String serviceName;
  
  Map<String, Object> parameters = new HashMap<String,Object>();

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  public Map<String, Object> getParameters() {
    return parameters;
  }

  public void setParameters(Map<String, Object> parameters) {
    this.parameters = parameters;
  }
  
}

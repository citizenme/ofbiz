package com.citizenme.integration.ofbiz.helper;

import java.util.Map;

public class Config {

  // Just a straight hash map of various parameters
  private Map<String,Object> parameters;

  private Map<String, TaxAuthority> taxAuthorities;
  
  public Map<String, Object> getParameters() {
    return parameters;
  }

  public void setParameters(Map<String, Object> parameters) {
    this.parameters = parameters;
  }

  public Map<String, TaxAuthority> getTaxAuthorities() {
    return taxAuthorities;
  }

  public void setTaxAuthorities(Map<String, TaxAuthority> taxAuthorities) {
    this.taxAuthorities = taxAuthorities;
  }
  
}

package com.citizenme.integration.ofbiz.helper;

import java.util.Map;

public class Config {

  // Just a straight hash map of various parameters
  private Map<String,Object> parameters;

  private Map<String, TaxAuthority> taxAuthorities;
  
  private Map<String, PaymentProviderConfig> paymentProviderConfigs;
  
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
  
  public Object getParameter(String key) {
    return parameters.get(key);
  }
  
  public TaxAuthority getTaxAuthority(String key) {
    return taxAuthorities.get(key);
  }

  public Map<String, PaymentProviderConfig> getPaymentProviderConfigs() {
    return paymentProviderConfigs;
  }

  public void setPaymentProviderConfigs(Map<String, PaymentProviderConfig> paymentProviderConfigs) {
    this.paymentProviderConfigs = paymentProviderConfigs;
  }
  
  public PaymentProviderConfig getPaymentProviderConfig(String key) {
    return paymentProviderConfigs.get(key);
  }
  
}

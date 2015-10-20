package com.citizenme.integration.ofbiz.helper;

public class TaxAuthority {

  private String partyId;
  
  private String geoId;
  
  private String overrideGlAccountId;
  
  private String comments;
  
  private String rateSeqId;
  
  private String orderAdjustmentTypeId;

  public String getPartyId() {
    return partyId;
  }

  public void setPartyId(String partyId) {
    this.partyId = partyId;
  }

  public String getGeoId() {
    return geoId;
  }

  public void setGeoId(String geoId) {
    this.geoId = geoId;
  }

  public String getOverrideGlAccountId() {
    return overrideGlAccountId;
  }

  public void setOverrideGlAccountId(String overrideGlAccountId) {
    this.overrideGlAccountId = overrideGlAccountId;
  }

  public String getComments() {
    return comments;
  }

  public void setComments(String comments) {
    this.comments = comments;
  }

  public String getRateSeqId() {
    return rateSeqId;
  }

  public void setRateSeqId(String rateSeqId) {
    this.rateSeqId = rateSeqId;
  }

  public String getOrderAdjustmentTypeId() {
    return orderAdjustmentTypeId;
  }

  public void setOrderAdjustmentTypeId(String orderAdjustmentTypeId) {
    this.orderAdjustmentTypeId = orderAdjustmentTypeId;
  }
  
}

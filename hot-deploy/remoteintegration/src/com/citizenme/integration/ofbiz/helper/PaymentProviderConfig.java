package com.citizenme.integration.ofbiz.helper;

public class PaymentProviderConfig {

  private String id;
  
  private String finAccountId;
  
  private String chargeGlCreditAccountId;

  private String chargeGlDebitAccountId;
  
  private String paymentMethodTypeId;
  
  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getFinAccountId() {
    return finAccountId;
  }

  public void setFinAccountId(String finAccountId) {
    this.finAccountId = finAccountId;
  }

  public String getChargeGlCreditAccountId() {
    return chargeGlCreditAccountId;
  }

  public void setChargeGlCreditAccountId(String chargeGlCreditAccountId) {
    this.chargeGlCreditAccountId = chargeGlCreditAccountId;
  }

  public String getChargeGlDebitAccountId() {
    return chargeGlDebitAccountId;
  }

  public void setChargeGlDebitAccountId(String chargeGlDebitAccountId) {
    this.chargeGlDebitAccountId = chargeGlDebitAccountId;
  }

  public String getPaymentMethodTypeId() {
    return paymentMethodTypeId;
  }

  public void setPaymentMethodTypeId(String paymentMethodTypeId) {
    this.paymentMethodTypeId = paymentMethodTypeId;
  }
  
}

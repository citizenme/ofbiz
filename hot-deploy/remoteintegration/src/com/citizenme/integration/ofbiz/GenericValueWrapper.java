package com.citizenme.integration.ofbiz;

import java.util.HashMap;
import java.util.Map;

public class GenericValueWrapper implements ValueWrapper {

  private String entityName;

  private Map<String, Object> fields = new HashMap<String, Object>();

  public String getEntityName() {
    return entityName;
  }

  public void setEntityName(String entityName) {
    this.entityName = entityName;
  }

  public Map<String, Object> getFields() {
    return fields;
  }

  public void setFields(Map<String, Object> fields) {
    this.fields = fields;
  }

  public void put(String key, Object value) {
    fields.put(key, value);
  }
  
  public Object get(String key) {
    return fields.get(key);
  }
  
}

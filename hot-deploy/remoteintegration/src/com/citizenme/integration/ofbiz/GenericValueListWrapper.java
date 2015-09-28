package com.citizenme.integration.ofbiz;

import java.util.LinkedList;
import java.util.List;

public class GenericValueListWrapper implements ValueWrapper {

  private List<GenericValueWrapper> values = new LinkedList<GenericValueWrapper>();

  public List<GenericValueWrapper> getValues() {
    return values;
  }

  public void setValues(List<GenericValueWrapper> values) {
    this.values = values;
  }

  public void add(GenericValueWrapper value) {
    values.add(value);
  }
  
  public void set(int index, GenericValueWrapper value) {
    values.set(index, value);
  }
  
  public GenericValueWrapper get(int index) {
    return values.get(index);
  }
  
}

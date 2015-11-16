package com.citizenme.integration.ofbiz.servlet;

import java.util.HashSet;
import java.util.Set;
 
import javax.ws.rs.core.Application;

public class RemoteIntegrationApplication extends Application {

  @Override
  public Set<Class<?>> getClasses() {
      Set<Class<?>> classes = new HashSet<Class<?>>();
      classes.add(CreateOrUpdateClientOrganisationResource.class);
      classes.add(CreateOrUpdateClientAgentResource.class);
      classes.add(CreatePanelSalesOrderResource.class);
      classes.add(ReceiveSalesOrderPaymentResource.class);
      classes.add(CancelSalesOrderResource.class);
      return classes;
  }  
}

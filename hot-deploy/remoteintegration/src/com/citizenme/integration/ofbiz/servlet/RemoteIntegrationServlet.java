package com.citizenme.integration.ofbiz.servlet;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;
import org.ofbiz.service.ServiceUtil;

import com.citizenme.integration.ofbiz.GenericValueListWrapper;
import com.citizenme.integration.ofbiz.GenericValueWrapper;
import com.citizenme.integration.ofbiz.Request;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javolution.util.FastList;
import javolution.util.FastMap;

public class RemoteIntegrationServlet extends HttpServlet {

  private ObjectMapper om;

  private GenericDelegator delegator;
  private LocalDispatcher dispatcher;
  
  /**
   * 
   */
  private static final long serialVersionUID = -7923894804798748714L;

  @Override
  public void init(ServletConfig config) throws ServletException {
      super.init(config);
      om = new ObjectMapper();
      om.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
      om.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
      //Not working: om.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
      om.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
      om.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
      om.enableDefaultTyping(); // default to using DefaultTyping.OBJECT_AND_NON_CONCRETE

      delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
      dispatcher = ServiceContainer.getLocalDispatcher(delegator.getDelegatorName(), delegator);
  }

  @Override
  public void destroy() {
      super.destroy();
  }

  
  public void writeResponse(HttpServletResponse response, String value) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.getWriter().append(value);
    response.getWriter().flush();
    response.getWriter().close();
    

  }
  
  public GenericValue convertWrapper(GenericValueWrapper wrapper) {
    
    
    
    return null;
  }
  
  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

//    StringBuffer jb = new StringBuffer();
//    String line = null;
//      BufferedReader reader = request.getReader();
//      while ((line = reader.readLine()) != null)
//        jb.append(line);
    
    Map<String, String> paramMap = UtilMisc.toMap(
        "message", "message",
        "login.username", "test",
        "login.password", "test"
    );
    
    Request r = om.readValue(request.getInputStream(), Request.class);

    Map<String, Object> requestMap = new HashMap<String, Object>();
    
    for (Map.Entry<String, Object> e : r.getParameters().entrySet()) {
      
      if (e.getValue() instanceof GenericValueListWrapper) {
        
        GenericValueListWrapper gvlw = (GenericValueListWrapper) e.getValue();
        
        List<GenericValue> genericValues = FastList.newInstance();
        
        for (GenericValueWrapper gvw : gvlw.getValues()) {
          GenericValue gv = delegator.makeValue(gvw.getEntityName(), gvw.getFields());
          genericValues.add(gv);
        }
        
        requestMap.put(e.getKey(), genericValues);
        
      } else if (e.getValue() instanceof GenericValueWrapper) {

        GenericValueWrapper gvw = (GenericValueWrapper) e.getValue();
        
        GenericValue genericValue = delegator.makeValue(gvw.getEntityName(), gvw.getFields());
        
        requestMap.put(e.getKey(), genericValue);
        
      } else {
        requestMap.put(e.getKey(), e.getValue());
      }
    }
    
    
    Map<String, Object> result = FastMap.newInstance();
    try {
      result = dispatcher.runSync(r.getServiceName(), requestMap);
    } catch (GenericServiceException e1) {
      Debug.logError(e1, RemoteIntegrationServlet.class.getName());
      throw new RuntimeException(e1);
    }
    
    if (ServiceUtil.isSuccess(result)) {
      writeResponse(response, om.writeValueAsString(result));
    }
  
    if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
      writeResponse(response, "{ \"status\": false, \"message\": \"error\" }");
    }

            
  }

  
  
}

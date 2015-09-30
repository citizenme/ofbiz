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
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;
import org.ofbiz.service.ServiceUtil;

import com.citizenme.integration.ofbiz.model.GenericValueListWrapper;
import com.citizenme.integration.ofbiz.model.GenericValueWrapper;
import com.citizenme.integration.ofbiz.model.Request;
import com.citizenme.integration.ofbiz.model.Response;
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

    Response ofbizResponse = new Response();

    try {
      Request ofbizRequest = om.readValue(request.getInputStream(), Request.class);
  
      ofbizResponse.setServiceName(ofbizRequest.getServiceName());
      
      Map<String, Object> requestMap = new HashMap<String, Object>();
      
      for (Map.Entry<String, Object> e : ofbizRequest.getParameters().entrySet()) {
        
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
  
      result = dispatcher.runSync(ofbizRequest.getServiceName(), requestMap);
    
      if (ServiceUtil.isSuccess(result)) {
      
        ofbizResponse.setStatus(true);
        ofbizResponse.setMessage("OK");
        
        for (Map.Entry<String, Object> e : result.entrySet()) {
          
          if (e.getValue() instanceof GenericValue) {

            GenericValue gv = (GenericValue) e.getValue();
            GenericValueWrapper gvw = new GenericValueWrapper();
            
            gvw.setEntityName(gv.getEntityName());
            
            for (Map.Entry<String, Object> e1 : gv.entrySet()) {
              gvw.put(e1.getKey(), e1.getValue());
            }
            ofbizResponse.put(e.getKey(), gvw);

          } else if (e.getValue() instanceof List<?>) {
            
            List<?> l1 = (List<?>) e.getValue();
            
            // Cheating by checking if first element in list is of a known type GenericValue (use reflecting instead?)
            if (l1.size() > 0 && l1.get(0) instanceof GenericValue) {
              
              List<GenericValue> l2 = (List<GenericValue>) l1;
              
              GenericValueListWrapper gvlw = new GenericValueListWrapper();
              
              for (GenericValue gv : l2) {
                
                GenericValueWrapper gvw = new GenericValueWrapper();
                
                gvw.setEntityName(gv.getEntityName());
                
                for (Map.Entry<String, Object> e1 : gv.entrySet()) {
                  gvw.put(e1.getKey(), e1.getValue());
                }
                
                gvlw.add(gvw);
              }
            
              ofbizResponse.put(e.getKey(), gvlw);
            } else {
              // Arbitrary list - not sure what it might be - but returning it just in case
              ofbizResponse.put(e.getKey(), e.getValue());
            }
          } else {
            ofbizResponse.put(e.getKey(), e.getValue());
          }
        }
      }
    
      if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
        ofbizResponse.setStatus(false);
        ofbizResponse.setMessage("Failed");
      }

      writeResponse(response, om.writeValueAsString(ofbizResponse));

    } catch (GenericServiceException | IllegalArgumentException | IOException e) {
      Debug.logError(e, RemoteIntegrationServlet.class.getName());
      ofbizResponse.setStatus(false);
      ofbizResponse.setMessage("Failed: " + e.getMessage());
      writeResponse(response, om.writeValueAsString(ofbizResponse));
      throw new RuntimeException(e);
    }
            
  }
  
}

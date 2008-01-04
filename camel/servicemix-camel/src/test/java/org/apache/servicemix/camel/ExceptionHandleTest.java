/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.servicemix.camel;

import java.net.URL;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;

import org.apache.camel.CamelContext;
import org.apache.camel.ContextTestSupport;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.cxf.transport.CamelTransportFactory;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.bus.spring.SpringBusFactory;
import org.apache.cxf.endpoint.ServerImpl;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.apache.cxf.message.Message;
import org.apache.cxf.transport.ConduitInitiatorManager;
import org.apache.hello_world_soap_http.BadRecordLitFault;
import org.apache.hello_world_soap_http.Greeter;
import org.apache.hello_world_soap_http.GreeterImpl;
import org.apache.hello_world_soap_http.NoSuchCodeLitFault;
import org.apache.hello_world_soap_http.SOAPService;
import org.apache.servicemix.nmr.api.NMR;
import org.apache.servicemix.nmr.core.ServiceMix;


public class ExceptionHandleTest extends ContextTestSupport {
	protected static final String ROUTER_ADDRESS = "camel://jetty:http://localhost:9000/SoapContext/SoapPort";
    protected static final String SERVICE_ADDRESS = "local://smx/hello_world";
    protected static final String SERVICE_CLASS = "serviceClass=org.apache.hello_world_soap_http.Greeter";
    private static final String WSDL_LOCATION = "wsdlURL=/wsdl/hello_world.wsdl";
    private static final String SERVICE_NAME = "serviceName=%7bhttp://apache.org/hello_world_soap_http%7dSOAPService";
    
    
    
    private String routerEndpointURI = "cxf://" + ROUTER_ADDRESS + "?" + SERVICE_CLASS 
    	+ "&" + WSDL_LOCATION + "&" + SERVICE_NAME + "&dataFormat=POJO";
    private String serviceEndpointURI = "cxf://" + SERVICE_ADDRESS + "?" + SERVICE_CLASS 
    	+ "&" + WSDL_LOCATION + "&" + SERVICE_NAME + "&dataFormat=POJO";
    
    private ServerImpl server;
    private CamelContext camelContext;
    private ServiceMixComponent smxComponent;
    private NMR nmr;
    
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();        
                
        startService();
    }
    
    protected void startService() {
    	Object implementor = new GreeterImpl();
        
    	javax.xml.ws.Endpoint.publish(SERVICE_ADDRESS, implementor);
        
  
    }
    
    @Override
    protected void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
    }
  
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            public void configure() {
            	from(routerEndpointURI).to("smx:testEndpoint");// like what do in binding component
            	from("smx:testEndpoint").to(serviceEndpointURI);// like what do in se
            }
        };
    }
    
    protected CamelContext createCamelContext() throws Exception {
    	camelContext = new DefaultCamelContext();
    	Bus bus = BusFactory.getDefaultBus();
    	CamelTransportFactory camelTransportFactory = (CamelTransportFactory) bus.getExtension(ConduitInitiatorManager.class)
        	.getConduitInitiator(CamelTransportFactory.TRANSPORT_ID);
    	camelTransportFactory.setCamelContext(camelContext);
    	smxComponent = new ServiceMixComponent();
    	nmr = new ServiceMix();
    	((ServiceMix)nmr).init();
    	smxComponent.setNmr(nmr);
    	camelContext.addComponent("smx", smxComponent);
        return camelContext;
    }
    
    public void testException() throws Exception {  
       
    	URL wsdl = getClass().getResource("/wsdl/hello_world.wsdl");
        assertNotNull(wsdl);
        SOAPService service1 = new SOAPService(wsdl, new QName(
                "http://apache.org/hello_world_soap_http", "SOAPService"));
        Greeter greeter = service1.getSoapPort();
        ClientProxy.getClient(greeter).getInInterceptors().add(new LoggingInInterceptor());
        ClientProxy.getClient(greeter).getOutInterceptors().add(new LoggingOutInterceptor());
        String ret = greeter.sayHi();
        assertEquals(ret, "Bonjour");
        String noSuchCodeFault = "NoSuchCodeLitFault";
        String badRecordFault = "BadRecordLitFault";
        try {
            greeter.testDocLitFault(noSuchCodeFault);
            fail("Should have thrown NoSuchCodeLitFault exception");
        } catch (NoSuchCodeLitFault nslf) {
            assertNotNull(nslf.getFaultInfo());
            assertNotNull(nslf.getFaultInfo().getCode());
        } 
        
        try {
            greeter.testDocLitFault(badRecordFault);
            fail("Should have thrown BadRecordLitFault exception");
        } catch (BadRecordLitFault brlf) {                
            BindingProvider bp = (BindingProvider)greeter;
            Map<String, Object> responseContext = bp.getResponseContext();
            String contentType = (String) responseContext.get(Message.CONTENT_TYPE);
            assertEquals("text/xml", contentType);
            Integer responseCode = (Integer) responseContext.get(Message.RESPONSE_CODE);
            assertEquals(200, responseCode.intValue());                
            assertNotNull(brlf.getFaultInfo());
            assertEquals("BadRecordLitFault", brlf.getFaultInfo());
        }
                      
    }
    
    
    public void testOneway() throws Exception {
    	URL wsdl = getClass().getResource("/wsdl/hello_world.wsdl");
        assertNotNull(wsdl);
        SOAPService service1 = new SOAPService(wsdl, new QName(
                "http://apache.org/hello_world_soap_http", "SOAPService"));
        Greeter greeter = service1.getSoapPort();
        ClientProxy.getClient(greeter).getInInterceptors().add(new LoggingInInterceptor());
        ClientProxy.getClient(greeter).getOutInterceptors().add(new LoggingOutInterceptor());
        greeter.greetMeOneWay("test oneway");
    }
    
    public void testGetTransportFactoryFromBus() throws Exception {
    	Bus bus = BusFactory.getDefaultBus();
    	
    	assertNotNull(bus.getExtension(ConduitInitiatorManager.class)
        	.getConduitInitiator(CamelTransportFactory.TRANSPORT_ID));
    }
}

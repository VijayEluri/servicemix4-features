/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.servicemix.examples.cxf;

import java.util.HashMap;
import java.util.Map;

import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor;

public class CustomerSecurityInterceptor extends AbstractPhaseInterceptor<Message> {

    public CustomerSecurityInterceptor() {
        super(Phase.SETUP);
    }

    public void handleMessage(Message message) throws Fault {
        Map<String, Object> outProps = new HashMap<String, Object>();
        outProps.put("action", "UsernameToken");

        outProps.put("passwordType", "PasswordText");
        outProps.put("user", "smx");
        outProps.put("passwordCallbackClass", "org.apache.servicemix.examples.cxf.ClientPasswordCallback");
        for (Interceptor inteceptor : message.getInterceptorChain()) {
            //set properties for WSS4JOutInterceptor
            if (inteceptor.getClass().getName().equals("org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor")) {
                ((WSS4JOutInterceptor)inteceptor).setProperties(outProps);
            }
        }
    }

 
}

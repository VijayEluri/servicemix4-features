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

package org.apache.servicemix.cxf.transport.nmr;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.activation.DataHandler;
import javax.jws.WebService;
import javax.security.auth.Subject;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import org.apache.cxf.Bus;
import org.apache.cxf.attachment.AttachmentImpl;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.message.Attachment;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.apache.cxf.phase.PhaseInterceptorChain;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.apache.cxf.transport.MessageObserver;
import org.apache.cxf.workqueue.AutomaticWorkQueue;
import org.apache.cxf.workqueue.WorkQueueManager;
import org.apache.cxf.ws.addressing.EndpointReferenceType;
import org.apache.cxf.wsdl.EndpointReferenceUtils;
import org.apache.servicemix.nmr.api.Channel;
import org.apache.servicemix.nmr.api.Endpoint;
import org.apache.servicemix.nmr.api.NMR;
import org.apache.servicemix.nmr.api.Pattern;
import org.apache.servicemix.nmr.api.Reference;
import org.apache.servicemix.nmr.api.Status;

public class NMRConduitOutputStream extends CachedOutputStream {

    private static final Logger LOG = LogUtils.getL7dLogger(NMRConduitOutputStream.class);

    private Message message;
    private boolean isOneWay;
    private Channel channel;
    private NMRConduit conduit;
    private EndpointReferenceType target;
    private boolean hasLoggedAsyncWarning;

    public NMRConduitOutputStream(Message m, NMR nmr, EndpointReferenceType target,
                                  NMRConduit conduit) {
        message = m;
        this.channel = nmr.createChannel();
        this.conduit = conduit;
        this.target = target;
        
    }

    @Override
    protected void doFlush() throws IOException {

    }

    @Override
    protected void doClose() throws IOException {
        isOneWay = message.getExchange().isOneWay();
        commitOutputMessage();
        if (target != null) {
            target.getClass();
        }
    }

    private void syncInvoke(org.apache.servicemix.nmr.api.Exchange xchng) throws Exception {
        try {
            if (!isOneWay) {
                channel.sendSync(xchng);
                Source content = null;
                org.apache.servicemix.nmr.api.Message nm = null;
                if (xchng.getFault(false) != null) {
                    content = xchng.getFault().getBody(Source.class);
                    nm = xchng.getFault();
                } else {
                    content = xchng.getOut().getBody(Source.class);
                    nm = xchng.getOut();
                }
                Message inMessage = new MessageImpl();
                message.getExchange().setInMessage(inMessage);
                InputStream ins = NMRMessageHelper.convertMessageToInputStream(content);
                if (ins == null) {
                    throw new IOException(new org.apache.cxf.common.i18n.Message("UNABLE.RETRIEVE.MESSAGE", LOG).toString());
                }
                inMessage.setContent(InputStream.class, ins);
                //copy attachments
                Collection<Attachment> cxfAttachmentList = new ArrayList<Attachment>();
                for (Map.Entry<String, Object> ent : nm.getAttachments().entrySet()) {
                    cxfAttachmentList.add(new AttachmentImpl(ent.getKey(), (DataHandler) ent.getValue()));
                }
                inMessage.setAttachments(cxfAttachmentList);

                //copy properties
                for (Map.Entry<String, Object> ent : nm.getHeaders().entrySet()) {
                    if (!ent.getKey().equals(Message.REQUESTOR_ROLE)) {
                        inMessage.put(ent.getKey(), ent.getValue());
                    }
                }

                //copy securitySubject
                inMessage.put(NMRTransportFactory.NMR_SECURITY_SUBJECT, nm.getSecuritySubject());

                conduit.getMessageObserver().onMessage(inMessage);

                xchng.setStatus(Status.Done);
                channel.send(xchng);
            } else {
                channel.sendSync(xchng);
            }
        } finally {
            channel.close();
        }

    }

    private void asynInvokeWithWorkQueue(final org.apache.servicemix.nmr.api.Exchange exchange) throws Exception {
        Runnable runnable = new Runnable() {
            public void run() {
                try {
                    syncInvoke(exchange);
                } catch (Throwable e) {
                    ((PhaseInterceptorChain)message.getInterceptorChain()).abort();
                    message.setContent(Exception.class, e);
                    ((PhaseInterceptorChain)message.getInterceptorChain()).unwind(message);
                    MessageObserver mo = message.getInterceptorChain().getFaultObserver();
                    if (mo == null) {
                        mo = message.getExchange().get(MessageObserver.class);
                    }
                    mo.onMessage(message);
                }
            }
        };

        try {
            Executor ex = message.getExchange().get(Executor.class);
            if (ex != null) {
                message.getExchange().put(Executor.class.getName()
                                + ".USING_SPECIFIED", Boolean.TRUE);
                ex.execute(runnable);
            } else {
                WorkQueueManager mgr = message.getExchange().get(Bus.class)
                        .getExtension(WorkQueueManager.class);
                AutomaticWorkQueue qu = mgr.getNamedWorkQueue("nmr-conduit");
                if (qu == null) {
                    qu = mgr.getAutomaticWorkQueue();
                }
                // need to set the time out somewhere
                qu.execute(runnable);
            }
        } catch (RejectedExecutionException rex) {
            if (!hasLoggedAsyncWarning) {
                LOG.warning("Executor rejected background task to retrieve the response.  Suggest increasing the workqueue settings.");
                hasLoggedAsyncWarning = true;
            }
            LOG.info("Executor rejected background task to retrieve the response, running on current thread.");
            syncInvoke(exchange);
        }

    }

    private void commitOutputMessage() throws IOException {
        try {
            Member member = (Member) message.get(Method.class.getName());
            Class<?> clz = member.getDeclaringClass();
            Exchange exchange = message.getExchange();
            BindingOperationInfo bop = exchange.get(BindingOperationInfo.class);

            LOG.fine(new org.apache.cxf.common.i18n.Message("INVOKE.SERVICE", LOG).toString() + clz);

            WebService ws = clz.getAnnotation(WebService.class);
            assert ws != null;
            QName interfaceName = new QName(ws.targetNamespace(), ws.name());
            QName serviceName;
            String address = null;
            String portName = null;
            if (target != null) {
                serviceName = EndpointReferenceUtils.getServiceName(target, conduit.getBus());
                address = EndpointReferenceUtils.getAddress(target);
                portName = EndpointReferenceUtils.getPortName(target);
            } else {
                serviceName = message.getExchange().get(org.apache.cxf.service.Service.class).getName();
            }
          
            LOG.fine(new org.apache.cxf.common.i18n.Message("CREATE.MESSAGE.EXCHANGE", LOG).toString() + serviceName);
            org.apache.servicemix.nmr.api.Exchange xchng;
            if (isOneWay) {
                xchng = channel.createExchange(Pattern.InOnly);
            } else if (bop.getOutput() == null) {
                xchng = channel.createExchange(Pattern.RobustInOnly);
            } else {
                xchng = channel.createExchange(Pattern.InOut);
            }

            org.apache.servicemix.nmr.api.Message inMsg = xchng.getIn();
            LOG.fine(new org.apache.cxf.common.i18n.Message("EXCHANGE.ENDPOINT", LOG).toString() + serviceName);
            LOG.fine("setup message contents on " + inMsg);
            inMsg.setBody(getMessageContent(message));
            //copy attachments
            if (message != null && message.getAttachments() != null) {
                for (Attachment att : message.getAttachments()) {
                    inMsg.addAttachment(att.getId(), att
                            .getDataHandler());
                }
            }
            
            //copy properties
            for (Map.Entry<String, Object> ent : message.entrySet()) {
                //check if value is Serializable, and if value is Map or collection,
                //just exclude it since the entry of it may not be Serializable as well
                if (ent.getValue() instanceof Serializable
                        && !(ent.getValue() instanceof Map)
                        && !(ent.getValue() instanceof Collection)) {
                    inMsg.setHeader(ent.getKey(), ent.getValue());
                }
            }

            // we need to copy the protocol headers
            Map<String, List<String>> protocolHeaders = (Map<String, List<String>>)message.get(Message.PROTOCOL_HEADERS);
            if (protocolHeaders != null) {
                for (Map.Entry<String, List<String>> ent : protocolHeaders.entrySet()) {
                    if (ent.getValue().size() > 0) {
                        // here we just put the first header value
                        inMsg.setHeader(ent.getKey(), ent.getValue().get(0));
                    }
                }
            }
            
            //copy securitySubject
            inMsg.setSecuritySubject((Subject) message.get(NMRTransportFactory.NMR_SECURITY_SUBJECT));
            
            LOG.fine("service for exchange " + serviceName);

            Map<String,Object> refProps = new HashMap<String,Object>();
            if (interfaceName != null) {
                refProps.put(Endpoint.INTERFACE_NAME, interfaceName.toString());
            }
            if (serviceName != null) {
                refProps.put(Endpoint.SERVICE_NAME, serviceName.toString());
            }
            
            if (address != null && address.startsWith("nmr:")) {
                if (address.indexOf("?") > 0) {
                    refProps.put(Endpoint.NAME, address.substring(4, address.indexOf("?")));
                } else {
                    refProps.put(Endpoint.NAME, address.substring(4));
                }
            } else {
                if (portName != null) {
                    refProps.put(Endpoint.NAME, portName);
                }
            }
            Reference ref = channel.getNMR().getEndpointRegistry().lookup(refProps);
            xchng.setTarget(ref);
            xchng.setOperation(bop.getName());
            LOG.fine("sending message");
            if (message.getExchange().isSynchronous()) {
                syncInvoke(xchng);
            } else {
                asynInvokeWithWorkQueue(xchng);
            }

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
            throw new IOException(e.toString());
        }

    }

    private Source getMessageContent(Message message2) throws IOException {
        return new StreamSource(this.getInputStream());
        
    }

    @Override
    protected void onWrite() throws IOException {

    }

}

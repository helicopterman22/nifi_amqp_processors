/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.amqp;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.jms.JMSException;

import org.apache.nifi.processors.amqp.util.QpidAmqpFactory;
import org.apache.nifi.processors.amqp.util.WrappedMessageConsumer;
import org.apache.nifi.processors.amqp.util.KeyStoreToJKS;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.TriggerWhenEmpty;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.logging.ProcessorLog;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.ssl.SSLContextService;


/**
 * This processor supports updating flowfile attributes and can do so
 * conditionally or unconditionally. Like the FlowFileMetadataEnhancer, it can
 * be configured with an arbitrary number of optional properties to define how
 * attributes should be updated. Each optional property represents an action
 * that is applied to all incoming flow files. An action is comprised of an
 * attribute key and a format string. The format string supports the following
 * parameters.
 **    REWRITE THIS BIT
 * Note: In order for configuration changes made in the custom UI to take
 * effect, the processor must be stopped and started.
 */
@EventDriven
@SideEffectFree
@TriggerWhenEmpty
@Tags({"amqp", "listen", "consume", "ssl", "queue", "topic"})
@CapabilityDescription("Pulls messages from an AMQP Queue, creating a FlowFile for each AMQP Message or bundle of messages")
public class GetAMQP extends AmqpConsumer {

    private final Queue<WrappedMessageConsumer> consumerQueue = new LinkedBlockingQueue<>();
    private volatile String keystore;
    private volatile String keystorePasswd;
    private volatile String truststore;
    private volatile String truststorePasswd;
    
    @OnScheduled
    public void inintializeSSSL(ProcessContext context) throws GeneralSecurityException, IOException {
    	SSLContextService sslCntxtSvc = context.getProperty(SSL_CONTEXT_SVC).asControllerService(SSLContextService.class);
    	if (sslCntxtSvc != null){
    		if (!sslCntxtSvc.isTrustStoreConfigured()){
    			throw new IllegalStateException("Chosen SSL Context Service does not have a TrustStore configured");
    		}
    		final String keystoreType = sslCntxtSvc.getKeyStoreType();
    		keystore = sslCntxtSvc.getKeyStoreFile();
    		keystorePasswd = sslCntxtSvc.getKeyStorePassword();
    		if (sslCntxtSvc.isKeyStoreConfigured()){
    			keystore = sslCntxtSvc.getKeyStoreFile();
        		keystorePasswd = sslCntxtSvc.getKeyStorePassword();
        		if (!keystoreType.equals("JKS")){
        			final Path dir = Paths.get("conf/amqp");
        			if (!Files.exists(dir)){
        				Files.createDirectory(dir);
        			}
        			keystore = KeyStoreToJKS.convertToJKS(keystore, keystorePasswd, dir, getIdentifier());
        		}
    		}
    		final String truststoreType = sslCntxtSvc.getTrustStoreType();
    		truststore = sslCntxtSvc.getTrustStoreFile();
    		truststorePasswd = sslCntxtSvc.getTrustStorePassword();
    		if (!truststoreType.equals("JKS")){
    			final Path dir = Paths.get("conf/amqp");
    			if (!Files.exists(dir)){
    				Files.createDirectory(dir);
    			}
    			truststore = KeyStoreToJKS.convertToJKS(truststore, truststorePasswd, dir, getIdentifier());
    		}
    	}
    	else{
    		keystore = null;
    		keystorePasswd = null;
    		truststore = null;
    		truststorePasswd = null;
    	}
    }
    
    @OnStopped
    public void cleanupResources(){
    	WrappedMessageConsumer wrappedConsumer = consumerQueue.poll();
    	while (wrappedConsumer != null){
    		wrappedConsumer.close(getLogger());
    		wrappedConsumer = consumerQueue.poll();
    	}
    }
    
    

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final ProcessorLog logger = getLogger();
        
        WrappedMessageConsumer wrappedConsumer = consumerQueue.poll();
        
        if (wrappedConsumer == null){
        	try{
        		wrappedConsumer = QpidAmqpFactory.createQueueMessageConsumer(context, keystore, keystorePasswd, truststore, truststorePasswd);
        		logger.info("Connected to AMQP server {}", new Object[] {context.getProperty(URL).getValue()}) ;
        	}
        	catch (JMSException | URISyntaxException e){
        		logger.error("Failed to connect to AMQP Server due to {}", new Object[] {e} );
        		context.yield();
        		return;
        	}
        }
        
        try{
        	super.consume(context, session, wrappedConsumer);
        	
        	//Time to close this connection due to inactivity
        	if (wrappedConsumer.noMessageCountExceedsLimit()){
        		//Close and reconnect right away
        		wrappedConsumer.close(logger);
        		try{      		
        		    wrappedConsumer = QpidAmqpFactory.createQueueMessageConsumer(context, keystore, keystorePasswd, truststore, truststorePasswd);
        		    logger.info("Connected to AMQP server {}", new Object[] {context.getProperty(URL).getValue()}) ;
        		}
        		catch (JMSException | URISyntaxException e){
            		logger.error("Failed to connect to AMQP Server due to {}", new Object[] {e} );
            		context.yield();
            		return;
            	}
        	}
        }
        finally{
        	if(!wrappedConsumer.isClosed()){
        		consumerQueue.offer(wrappedConsumer);
        	}
        }
    }  
}

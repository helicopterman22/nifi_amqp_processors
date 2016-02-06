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

/*import static org.apache.nifi.processors.amqp.util.AmqpProperties.ACKNOWLEDGEMENT_MODE;
import static org.apache.nifi.processors.amqp.util.AmqpProperties.ACK_MODE_CLIENT;
import static org.apache.nifi.processors.amqp.util.AmqpProperties.BATCH_SIZE;
import static org.apache.nifi.processors.amqp.util.AmqpProperties.CLIENT_ID_PREFIX;
import static org.apache.nifi.processors.amqp.util.AmqpProperties.DESTINATION_NAME;
import static org.apache.nifi.processors.amqp.util.AmqpProperties.AMQP_PROPS_TO_ATTRIBUTES;
import static org.apache.nifi.processors.amqp.util.AmqpProperties.SERVICE_PROVIDER;
import static org.apache.nifi.processors.amqp.util.AmqpProperties.MESSAGE_SELECTOR;
import static org.apache.nifi.processors.amqp.util.AmqpProperties.SSL_CONTEXT_SVC;
import static org.apache.nifi.processors.amqp.util.AmqpProperties.TIMEOUT;
import static org.apache.nifi.processors.amqp.util.AmqpProperties.URL;
*/
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ProcessorLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.amqp.util.QpidAmqpFactory;
import org.apache.nifi.processors.amqp.util.WrappedMessageConsumer;
import org.apache.nifi.ssl.SSLContextService;
import org.apache.nifi.stream.io.BufferedOutputStream;
import org.apache.nifi.util.BooleanHolder;
import org.apache.nifi.util.IntegerHolder;
import org.apache.nifi.util.LongHolder;
import org.apache.nifi.util.ObjectHolder;
import org.apache.nifi.util.StopWatch;

public abstract class AmqpConsumer extends AbstractProcessor {
	
	///////////////////////////////
	  public static final String QPID_AMQP_PROVIDER = "Qpid";
	    public static final String ACK_MODE_CLIENT = "Client Acknowledge";
	    public static final String ACK_MODE_AUTO = "Auto Acknowledge";
	    public static final String MSG_TYPE_BYTE = "byte";
	    public static final String MSG_TYPE_TEXT = "text";
	    public static final String MSG_TYPE_STREAM = "stream";
	    public static final String MSG_TYPE_MAP = "map";
	    public static final String MSG_TYPE_EMPTY = "empty";
	    public static final String JKS_FILE_PREFIX = "JMS-";
	    
	    
	    // Standard JMS Properties
	    public static final PropertyDescriptor SERVICE_PROVIDER = new PropertyDescriptor.Builder()
	            .name("AMQP Provider")
	            .description("The Provider used for the AMQP Server")
	            .required(true)
	            .allowableValues(QPID_AMQP_PROVIDER)
	            .defaultValue(QPID_AMQP_PROVIDER)
	            .build();
	    public static final PropertyDescriptor URL = new PropertyDescriptor.Builder()
	            .name("URL")
	            .description("The URL of the AMQP Server")
	            .addValidator(StandardValidators.URI_VALIDATOR)
	            .required(true)
	            .build();
	    public static final PropertyDescriptor TIMEOUT = new PropertyDescriptor.Builder()
	            .name("Communications Timeout")
	            .description("The amount of time to wait when attempting to receive a message before giving up and assuming failure")
	            .required(true)
	            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
	            .defaultValue("30 sec")
	            .build();
	 
	    public static final PropertyDescriptor CLIENT_ID_PREFIX = new PropertyDescriptor.Builder()
	            .name("Client ID Prefix")
	            .description("A human-readable ID that can be used to associate connections with yourself so that the maintainers of the AMQP Server know who to contact if problems arise")
	            .required(false)
	            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
	            .build();
	    // Exchange Topic/Queue determination Properties
	    public static final PropertyDescriptor DESTINATION_NAME = new PropertyDescriptor.Builder()
	            .name("Destination Name")
	            .description("The name of the AMQP Exchange, Topic or queue to use")
	            .required(true)
	            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
	            .build();
	    
	    public static final PropertyDescriptor SSL_CONTEXT_SVC = new PropertyDescriptor.Builder()
	    		.name("SSL Context Service Id")
	    		.description("The ID of the SSL Context Controller Service. Needed when using secure connections to AMQP")
	    		.required(false)
	    		.identifiesControllerService((Class<? extends ControllerService>) SSLContextService.class)
	    		.build();
	    
	    // AMQP Listener Properties
	    public static final PropertyDescriptor BATCH_SIZE = new PropertyDescriptor.Builder()
	            .name("Message Batch Size")
	            .description("The number of messages to pull/push in a single iteration of the processor")
	            .required(true)
	            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
	            .defaultValue("10")
	            .build();
	    public static final PropertyDescriptor ACKNOWLEDGEMENT_MODE = new PropertyDescriptor.Builder()
	            .name("Acknowledgement Mode")
	            .description("The AMQP Acknowledgement Mode. Using Auto Acknowledge can cause messages to be lost on restart of NiFi but may provide better performance than Client Acknowledge.")
	            .required(true)
	            .allowableValues(ACK_MODE_CLIENT, ACK_MODE_AUTO)
	            .defaultValue(ACK_MODE_CLIENT)
	            .build();
	    public static final PropertyDescriptor AMQP_PROPS_TO_ATTRIBUTES = new PropertyDescriptor.Builder()
	            .name("Copy AMQP Properties to Attributes")
	            .description("Whether or not the AMQP Message Properties should be copied to the FlowFile Attributes; if so, the attribute name will be amqp.XXX, where XXX is the AMQP Property name")
	            .required(true)
	            .allowableValues("true", "false")
	            .defaultValue("true")
	            .build();
	    public static final PropertyDescriptor MESSAGE_SELECTOR = new PropertyDescriptor.Builder()
	            .name("Message Selector")
	            .description("The AMQP Message Selector to use in order to narrow the messages that are pulled")
	            .required(false)
	            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
	            .build();

	    
	///////////////////////////////

	public static final Relationship REL_SUCCESS = new Relationship.Builder().name("success")
			.description("All FlowFiles are routed to success").build();

	private final Set<Relationship> relationships;
	private final List<PropertyDescriptor> propertyDescriptors;

	public AmqpConsumer() {
		final Set<Relationship> rels = new HashSet<>();
		rels.add(REL_SUCCESS);
		this.relationships = Collections.unmodifiableSet(rels);

		final List<PropertyDescriptor> descriptors = new ArrayList<>();
		descriptors.add(SERVICE_PROVIDER);
		descriptors.add(URL);
		descriptors.add(DESTINATION_NAME);
		descriptors.add(TIMEOUT);
		descriptors.add(BATCH_SIZE);
		descriptors.add(SSL_CONTEXT_SVC);
		descriptors.add(ACKNOWLEDGEMENT_MODE);
		descriptors.add(MESSAGE_SELECTOR);
		descriptors.add(AMQP_PROPS_TO_ATTRIBUTES);
		descriptors.add(CLIENT_ID_PREFIX);
		this.propertyDescriptors = Collections.unmodifiableList(descriptors);
	}

	@Override
	public Set<Relationship> getRelationships() {
		return relationships;
	}

	@Override
	protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
		return propertyDescriptors;
	}

	public void consume(final ProcessContext context, final ProcessSession session,
			final WrappedMessageConsumer wrappedConsumer) throws ProcessException {
		final ProcessorLog logger = getLogger();

		final MessageConsumer consumer = wrappedConsumer.getConsumer();
		final boolean clientAcknowledge = context.getProperty(ACKNOWLEDGEMENT_MODE).getValue()
				.equalsIgnoreCase(ACK_MODE_CLIENT);
		final long timeout = context.getProperty(TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS);
		final boolean addAttributes = context.getProperty(AMQP_PROPS_TO_ATTRIBUTES).asBoolean();
		final int batchSize = context.getProperty(BATCH_SIZE).asInteger();
		final ObjectHolder<Message> lastMessageReceived = new ObjectHolder<>(null);
		final ObjectHolder<Map<String, String>> attributesFromJmsProps = new ObjectHolder<>(null);
		final Set<FlowFile> allFlowFilesCreated = new HashSet<>();
		final IntegerHolder messagesReceived = new IntegerHolder(0);
		final LongHolder bytesReceived = new LongHolder(0L);
		final StopWatch stopWatch = new StopWatch(true);

		for (int i = 0; i < batchSize; i++) {
			final BooleanHolder failure = new BooleanHolder(false);
			final Message message;
			try {
				// If we haven't received a message, wait until one is
				// available. If we have already received at least one
				// message, then we are not willing to wait for more to become
				// available, but we are willing to keep receiving
				// all messages that are immediately available.
				if (messagesReceived.get().intValue() == 0) {
					message = consumer.receive(timeout);
				} else {
					message = consumer.receiveNoWait();
				}
			} catch (final JMSException e) {
				logger.error("Failed to receive AMQP Message due to {}", e);
				wrappedConsumer.close(logger);
				failure.set(true);
				break;
			}

			if (message == null) { // if no messages, we're done
				break;
			}
			wrappedConsumer.resetNoMessageCount();

			final IntegerHolder msgsThisFlowFile = new IntegerHolder(0);

			FlowFile flowFile = session.create();
			try {

				flowFile = session.write(flowFile, new OutputStreamCallback() {
					@Override
					public void process(final OutputStream rawOut) throws IOException {
						try (final OutputStream out = new BufferedOutputStream(rawOut, 65536)) {
							messagesReceived.getAndIncrement();
							final Map<String, String> attributes = (addAttributes
									? QpidAmqpFactory.createAttributeMap(message) : null);
							attributesFromJmsProps.set(attributes);

							final byte[] messageBody = QpidAmqpFactory.createByteArray(message);
							out.write(messageBody);
							bytesReceived.addAndGet(messageBody.length);
							msgsThisFlowFile.incrementAndGet();
							lastMessageReceived.set(message);
						} catch (final JMSException e) {
							logger.error("Failed to receive AMQP Message due to {}", new Object[] { e });
							failure.set(true);
							return;
						}
					}
				});
			} finally {
				if (failure.get()) {
					session.remove(flowFile);
					wrappedConsumer.close(logger);
					break;
				} else {
					allFlowFilesCreated.add(flowFile);
					final Map<String, String> attributes = attributesFromJmsProps.get();
					if (attributes != null) {
						flowFile = session.putAllAttributes(flowFile, attributes);
					}
					session.getProvenanceReporter().receive(flowFile, context.getProperty(URL).getValue());
					session.transfer(flowFile, REL_SUCCESS);
					logger.info("Created {} from {} messages received from JMS Server and transferred to 'success'",
							new Object[] { flowFile, msgsThisFlowFile.get() });
				}
			}

		}

		if (allFlowFilesCreated.isEmpty()) {
			wrappedConsumer.incrementNoMessageCount();
			context.yield();
			return;
		}

		session.commit();
		stopWatch.stop();

		if (!allFlowFilesCreated.isEmpty()) {
			final float secs = ((float) stopWatch.getDuration(TimeUnit.MILLISECONDS) / 1000F);
			float messagesPerSec = ((float) messagesReceived.get()) / secs;
			final String dataRate = stopWatch.calculateDataRate(bytesReceived.get());
			logger.info("Received {} messages in {} milliseconds, at a rate of {} messages/sec or {}", new Object[] {
					messagesReceived.get(), stopWatch.getDuration(TimeUnit.MILLISECONDS), messagesPerSec, dataRate });
		}

		// Acknowledge message if required
		final Message lastMessage = lastMessageReceived.get();
		if (clientAcknowledge && lastMessage != null) {
			try {
				lastMessage.acknowledge();
			} catch (final JMSException e) {
				logger.error(
						"Failed to acknowledge {} AMQP message(s). This may result in duplicate messages. reason for failure: {}",
						new Object[] { messagesReceived.get(), e });
			}
		}
	}
}

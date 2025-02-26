/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.pulsar.listener;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.BatchReceivePolicy;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncListenableTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.lang.Nullable;
import org.springframework.pulsar.core.PulsarConsumerFactory;
import org.springframework.pulsar.event.ConsumerFailedToStartEvent;
import org.springframework.pulsar.event.ConsumerStartedEvent;
import org.springframework.pulsar.event.ConsumerStartingEvent;
import org.springframework.scheduling.SchedulingAwareRunnable;
import org.springframework.util.StringUtils;
import org.springframework.util.concurrent.ListenableFuture;

/**
 * @author Soby Chacko
 */
public class DefaultPulsarMessageListenerContainer<T> extends AbstractPulsarMessageListenerContainer<T> {

	private volatile boolean running = false;

	private String beanName;

	private volatile ListenableFuture<?> listenerConsumerFuture;

	private volatile Listener listenerConsumer;

	private volatile CountDownLatch startLatch = new CountDownLatch(1);

	private final AbstractPulsarMessageListenerContainer<?> thisOrParentContainer;

	public DefaultPulsarMessageListenerContainer(PulsarConsumerFactory<? super T> pulsarConsumerFactory, PulsarContainerProperties pulsarContainerProperties) {
		super(pulsarConsumerFactory, pulsarContainerProperties);
		this.thisOrParentContainer = this;
	}

	@Override
	public void start() {
		try {
			doStart();
		}
		catch (PulsarClientException e) {
			e.printStackTrace(); //TODO: proper logging
		}
	}

	private void doStart() throws PulsarClientException {

		PulsarContainerProperties containerProperties = getPulsarContainerProperties();

		Object messageListenerObject = containerProperties.getMessageListener();
		AsyncListenableTaskExecutor consumerExecutor = containerProperties.getConsumerTaskExecutor();

		@SuppressWarnings("unchecked")
		MessageListener<T> messageListener = (MessageListener<T>) messageListenerObject;

		if (consumerExecutor == null) {
			consumerExecutor = new SimpleAsyncTaskExecutor(
					(getBeanName() == null ? "" : getBeanName()) + "-C-");
			containerProperties.setConsumerTaskExecutor(consumerExecutor);
		}

		this.listenerConsumer = new Listener(messageListener);
		setRunning(true);
		this.startLatch = new CountDownLatch(1);
		this.listenerConsumerFuture = consumerExecutor.submitListenable(this.listenerConsumer);

		try {
			if (!this.startLatch.await(containerProperties.getConsumerStartTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
				this.logger.error("Consumer thread failed to start - does the configured task executor "
						+ "have enough threads to support all containers and concurrency?");
				publishConsumerFailedToStart();
			}
		}
		catch (@SuppressWarnings("UNUSED") InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void stop() {
		setRunning(false);
		System.out.println("Pausing this consumer.");
		this.listenerConsumer.consumer.pause();
		try {
			System.out.println("Closing this consumer.");
			this.listenerConsumer.consumer.close();
		}
		catch (PulsarClientException e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	protected void setRunning(boolean running) {
		this.running = running;
	}

	/**
	 * Return the bean name.
	 *
	 * @return the bean name.
	 */
	@Nullable
	public String getBeanName() {
		return this.beanName;
	}

	@Override
	public void destroy() {

	}

	private void publishConsumerStartingEvent() {
		this.startLatch.countDown();
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerStartingEvent(this, this.thisOrParentContainer));
		}
	}

	private void publishConsumerStartedEvent() {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerStartedEvent(this, this.thisOrParentContainer));
		}
	}

	private void publishConsumerFailedToStart() {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerFailedToStartEvent(this, this.thisOrParentContainer));
		}
	}

	private final class Listener implements SchedulingAwareRunnable {

		private final MessageListener<T> listener;
		private final PulsarBatchMessageListener<T> batchMessageHandler;
		Consumer<T> consumer;

		private final PulsarContainerProperties containerProperties = getPulsarContainerProperties();

		private volatile Thread consumerThread;

		@SuppressWarnings({"unchecked", "rawtypes"})
		Listener(MessageListener<?> messageListener) {
			if (messageListener instanceof PulsarBatchMessageListener) {
				this.batchMessageHandler = (PulsarBatchMessageListener<T>) messageListener;
				this.listener = null;

			}
			else if (messageListener != null) {
				this.listener = (MessageListener<T>) messageListener;
				this.batchMessageHandler = null;
			}
			else {
				this.listener = null;
				this.batchMessageHandler = null;
			}

			try {
				final PulsarContainerProperties pulsarContainerProperties = getPulsarContainerProperties();
				Map<String, Object> propertiesToOverride = extractPropertiesToOverride(pulsarContainerProperties);

				final BatchReceivePolicy batchReceivePolicy = BatchReceivePolicy.DEFAULT_POLICY;
				this.consumer = getPulsarConsumerFactory().createConsumer(
						(Schema) pulsarContainerProperties.getSchema(),
						batchReceivePolicy, propertiesToOverride);
			}
			catch (PulsarClientException e) {
				e.printStackTrace(); //TODO - Proper logging
			}

		}

		private Map<String, Object> extractPropertiesToOverride(PulsarContainerProperties pulsarContainerProperties) {
			final SubscriptionType subscriptionType = pulsarContainerProperties.getSubscriptionType();
			final Map<String, Object> propertiesToOverride = new HashMap<>();
			if (subscriptionType != null) {
				propertiesToOverride.put("subscriptionType", subscriptionType);
			}
			final String[] topics = pulsarContainerProperties.getTopics();
			final HashSet<String> strings = new HashSet<>(Arrays.stream(topics).toList());
			if (!strings.isEmpty()) {
				propertiesToOverride.put("topicNames", strings);
			}
			if (StringUtils.hasText(pulsarContainerProperties.getSubscriptionName())) {
				propertiesToOverride.put("subscriptionName",
						pulsarContainerProperties.getSubscriptionName());
			}
			return propertiesToOverride;
		}

		@Override
		public boolean isLongLived() {
			return true;
		}

		@Override
		public void run() {
			publishConsumerStartingEvent();
			this.consumerThread = Thread.currentThread();

			publishConsumerStartedEvent();
			while (isRunning()) {
				Messages<T> messages = null;
				try {
					// Always receive messages in batch mode.
					messages = consumer.batchReceive();
					// TODO Async receive - both record and batch.
					if (this.containerProperties.isBatchListener()) {
						try {
							this.batchMessageHandler.received(consumer, messages);
							this.consumer.acknowledge(messages);
						}
						catch (Exception e) {
							// Message failed to process, redeliver later
							this.consumer.negativeAcknowledge(messages);
						}
					}
					else {
						for (Message<T> message : messages) {
							try {
								this.listener.received(consumer, message);
								if (this.containerProperties.getAckMode() != PulsarContainerProperties.AckMode.MANUAL) {
									this.consumer.acknowledge(message);
								}
							}
							catch (Exception e) {
								this.consumer.negativeAcknowledge(message);
							}
						}
					}
				}
				catch (Exception e) {
					if (messages != null) {
						this.consumer.negativeAcknowledge(messages);
					}
				}
			}
		}
	}
}

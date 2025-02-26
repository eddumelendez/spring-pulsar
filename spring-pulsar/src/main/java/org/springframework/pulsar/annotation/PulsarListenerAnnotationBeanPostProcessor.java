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

package org.springframework.pulsar.annotation;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.apache.commons.logging.LogFactory;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.schema.SchemaType;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.Scope;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.log.LogAccessor;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.lang.Nullable;
import org.springframework.messaging.converter.GenericMessageConverter;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.messaging.handler.annotation.support.MessageHandlerMethodFactory;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.pulsar.config.MethodPulsarListenerEndpoint;
import org.springframework.pulsar.config.PulsarListenerConfigUtils;
import org.springframework.pulsar.config.PulsarListenerContainerFactory;
import org.springframework.pulsar.config.PulsarListenerEndpointRegistrar;
import org.springframework.pulsar.config.PulsarListenerEndpointRegistry;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.Validator;

/**
 * Bean post-processor that registers methods annotated with {@link PulsarListener}
 * to be invoked by a Pulsar message listener container created under the covers
 * by a {@link org.springframework.pulsar.config.PulsarListenerContainerFactory}
 * according to the parameters of the annotation.
 *
 * <p>Annotated methods can use flexible arguments as defined by {@link PulsarListener}.
 *
 * <p>This post-processor is automatically registered by Spring's {@link EnablePulsar}
 * annotation.
 *
 * <p>Auto-detect any {@link PulsarListenerConfigurer} instances in the container,
 * allowing for customization of the registry to be used, the default container
 * factory or for fine-grained control over endpoints registration. See
 * {@link EnablePulsar} Javadoc for complete usage details.
 *
 * @param <K> the key type.
 * @param <V> the value type.
 *
 * @author Soby Chacko
 *
 * @see PulsarListener
 * @see EnablePulsar
 * @see PulsarListenerConfigurer
 * @see PulsarListenerEndpointRegistrar
 * @see PulsarListenerEndpointRegistry
 * @see org.springframework.pulsar.config.PulsarListenerEndpoint
 * @see MethodPulsarListenerEndpoint
 */
public class PulsarListenerAnnotationBeanPostProcessor<K, V> implements BeanPostProcessor, Ordered, ApplicationContextAware, InitializingBean, SmartInitializingSingleton {

	private final LogAccessor logger = new LogAccessor(LogFactory.getLog(getClass()));

	public static final String DEFAULT_PULSAR_LISTENER_CONTAINER_FACTORY_BEAN_NAME = "pulsarListenerContainerFactory";

	private static final String THE_LEFT = "The [";

	private static final String RESOLVED_TO_LEFT = "Resolved to [";

	private static final String RIGHT_FOR_LEFT = "] for [";

	private static final String GENERATED_ID_PREFIX = "org.springframework.Pulsar.PulsarListenerEndpointContainer#";

	private ApplicationContext applicationContext;
	private BeanFactory beanFactory;
	private BeanExpressionResolver resolver;
	private BeanExpressionContext expressionContext;
	private PulsarListenerEndpointRegistry endpointRegistry;

	private String defaultContainerFactoryBeanName = DEFAULT_PULSAR_LISTENER_CONTAINER_FACTORY_BEAN_NAME;

	private final PulsarListenerEndpointRegistrar registrar = new PulsarListenerEndpointRegistrar();
	private final PulsarHandlerMethodFactoryAdapter messageHandlerMethodFactory =
			new PulsarHandlerMethodFactoryAdapter();


	private Charset charset = StandardCharsets.UTF_8;

	private final Set<Class<?>> nonAnnotatedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>(64));

	private final ListenerScope listenerScope = new ListenerScope();


	private AnnotationEnhancer enhancer;

	private final AtomicInteger counter = new AtomicInteger();


	@Override
	public int getOrder() {
		return LOWEST_PRECEDENCE;
	}

	public void setEndpointRegistry(PulsarListenerEndpointRegistry endpointRegistry) {
		this.endpointRegistry = endpointRegistry;
	}

	public void setDefaultContainerFactoryBeanName(String containerFactoryBeanName) {
		this.defaultContainerFactoryBeanName = containerFactoryBeanName;
	}

	public void setCharset(Charset charset) {
		Assert.notNull(charset, "'charset' cannot be null");
		this.charset = charset;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		buildEnhancer();
	}

	private void buildEnhancer() {
		if (this.applicationContext != null) {
			Map<String, AnnotationEnhancer> enhancersMap =
					this.applicationContext.getBeansOfType(AnnotationEnhancer.class, false, false);
			if (enhancersMap.size() > 0) {
				List<AnnotationEnhancer> enhancers = enhancersMap.values()
						.stream()
						.sorted(new OrderComparator())
						.collect(Collectors.toList());
				this.enhancer = (attrs, element) -> {
					Map<String, Object> newAttrs = attrs;
					for (AnnotationEnhancer enh : enhancers) {
						newAttrs = enh.apply(newAttrs, element);
					}
					return attrs;
				};
			}
		}
	}

	@Override
	public void afterSingletonsInstantiated() {
		this.registrar.setBeanFactory(this.beanFactory);

		if (this.beanFactory instanceof ListableBeanFactory) {
			Map<String, PulsarListenerConfigurer> instances =
					((ListableBeanFactory) this.beanFactory).getBeansOfType(PulsarListenerConfigurer.class);
			for (PulsarListenerConfigurer configurer : instances.values()) {
				configurer.configurePulsarListeners(this.registrar);
			}
		}

		if (this.registrar.getEndpointRegistry() == null) {
			if (this.endpointRegistry == null) {
				Assert.state(this.beanFactory != null,
						"BeanFactory must be set to find endpoint registry by bean name");
				this.endpointRegistry = this.beanFactory.getBean(
						PulsarListenerConfigUtils.PULSAR_LISTENER_ENDPOINT_REGISTRY_BEAN_NAME,
						PulsarListenerEndpointRegistry.class);
			}
			this.registrar.setEndpointRegistry(this.endpointRegistry);
		}

		if (this.defaultContainerFactoryBeanName != null) {
			this.registrar.setContainerFactoryBeanName(this.defaultContainerFactoryBeanName);
		}

		// Set the custom handler method factory once resolved by the configurer
		MessageHandlerMethodFactory handlerMethodFactory = this.registrar.getMessageHandlerMethodFactory();
		if (handlerMethodFactory != null) {
			this.messageHandlerMethodFactory.setHandlerMethodFactory(handlerMethodFactory);
		}
		else {
			addFormatters(this.messageHandlerMethodFactory.defaultFormattingConversionService);
		}

		// Actually register all listeners
		this.registrar.afterPropertiesSet();
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(final Object bean, final String beanName) throws BeansException {
		if (!this.nonAnnotatedClasses.contains(bean.getClass())) {
			Class<?> targetClass = AopUtils.getTargetClass(bean);
			Collection<PulsarListener> classLevelListeners = findListenerAnnotations(targetClass);
			Map<Method, Set<PulsarListener>> annotatedMethods = MethodIntrospector.selectMethods(targetClass,
					(MethodIntrospector.MetadataLookup<Set<PulsarListener>>) method -> {
						Set<PulsarListener> listenerMethods = findListenerAnnotations(method);
						return (!listenerMethods.isEmpty() ? listenerMethods : null);
					});
			if (annotatedMethods.isEmpty()) {
				this.nonAnnotatedClasses.add(bean.getClass());
				this.logger.trace(() -> "No @PulsarListener annotations found on bean type: " + bean.getClass());
			}
			else {
				// Non-empty set of methods
				for (Map.Entry<Method, Set<PulsarListener>> entry : annotatedMethods.entrySet()) {
					Method method = entry.getKey();
					for (PulsarListener listener : entry.getValue()) {
						processPulsarListener(listener, method, bean, beanName);
					}
				}
				this.logger.debug(() -> annotatedMethods.size() + " @PulsarListener methods processed on bean '"
						+ beanName + "': " + annotatedMethods);
			}
		}
		return bean;
	}

	protected void processPulsarListener(PulsarListener pulsarListener, Method method, Object bean, String beanName) {
		Method methodToUse = checkProxy(method, bean);
		MethodPulsarListenerEndpoint<V> endpoint = new MethodPulsarListenerEndpoint<>();
		endpoint.setMethod(methodToUse);

		String beanRef = pulsarListener.beanRef();
		this.listenerScope.addListener(beanRef, bean);
		String[] topics = resolveTopics(pulsarListener);
		processListener(endpoint, pulsarListener, bean, beanName, topics);
		this.listenerScope.removeListener(beanRef);
	}

	protected void processListener(MethodPulsarListenerEndpoint<?> endpoint, PulsarListener PulsarListener,
								   Object bean, String beanName, String[] topics) {

		processPulsarListenerAnnotation(endpoint, PulsarListener, bean, topics);

		String containerFactory = resolve(PulsarListener.containerFactory());
		PulsarListenerContainerFactory<?> listenerContainerFactory = resolveContainerFactory(PulsarListener,
				containerFactory, beanName);

		this.registrar.registerEndpoint(endpoint, listenerContainerFactory);
	}

	@Nullable
	private PulsarListenerContainerFactory<?> resolveContainerFactory(PulsarListener PulsarListener,
																	  Object factoryTarget, String beanName) {

		String containerFactory = PulsarListener.containerFactory();
		if (!StringUtils.hasText(containerFactory)) {
			return null;
		}

		PulsarListenerContainerFactory<?> factory = null;

		Object resolved = resolveExpression(containerFactory);
		if (resolved instanceof PulsarListenerContainerFactory) {
			return (PulsarListenerContainerFactory<?>) resolved;
		}
		String containerFactoryBeanName = resolveExpressionAsString(containerFactory,
				"containerFactory");
		if (StringUtils.hasText(containerFactoryBeanName)) {
			assertBeanFactory();
			try {
				factory = this.beanFactory.getBean(containerFactoryBeanName, PulsarListenerContainerFactory.class);
			}
			catch (NoSuchBeanDefinitionException ex) {
				throw new BeanInitializationException(
						noBeanFoundMessage(factoryTarget, beanName, containerFactoryBeanName,
								PulsarListenerContainerFactory.class), ex);
			}
		}
		return factory;
	}

	protected void assertBeanFactory() {
		Assert.state(this.beanFactory != null, "BeanFactory must be set to obtain container factory by bean name");
	}

	protected String noBeanFoundMessage(Object target, String listenerBeanName, String requestedBeanName,
										Class<?> expectedClass) {

		return "Could not register Pulsar listener endpoint on ["
				+ target + "] for bean " + listenerBeanName + ", no '" + expectedClass.getSimpleName() + "' with id '"
				+ requestedBeanName + "' was found in the application context";
	}

	private void processPulsarListenerAnnotation(MethodPulsarListenerEndpoint<?> endpoint,
												 PulsarListener pulsarListener, Object bean, String[] topics) {

		endpoint.setBean(bean);
		endpoint.setMessageHandlerMethodFactory(this.messageHandlerMethodFactory);
		endpoint.setSubscriptionName(getEndpointSubscriptionName(pulsarListener));
		endpoint.setId(getEndpointId(pulsarListener));
		endpoint.setTopics(topics);
		endpoint.setSubscriptionType(getEndpointSubscriptionType(pulsarListener));
		endpoint.setSchemaType(pulsarListener.schemaType());

		String autoStartup = pulsarListener.autoStartup();
		if (StringUtils.hasText(autoStartup)) {
			endpoint.setAutoStartup(resolveExpressionAsBoolean(autoStartup, "autoStartup"));
		}
		resolvePulsarProperties(endpoint, pulsarListener.properties());
		if (StringUtils.hasText(pulsarListener.batch())) {
			endpoint.setBatchListener(Boolean.parseBoolean(pulsarListener.batch()));
		}
		endpoint.setBeanFactory(this.beanFactory);
	}

	private Boolean resolveExpressionAsBoolean(String value, String attribute) {
		Object resolved = resolveExpression(value);
		Boolean result = null;
		if (resolved instanceof Boolean) {
			result = (Boolean) resolved;
		}
		else if (resolved instanceof String) {
			result = Boolean.parseBoolean((String) resolved);
		}
		else if (resolved != null) {
			throw new IllegalStateException(
					THE_LEFT + attribute + "] must resolve to a Boolean or a String that can be parsed as a Boolean. "
							+ RESOLVED_TO_LEFT + resolved.getClass() + RIGHT_FOR_LEFT + value + "]");
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private void resolvePulsarProperties(MethodPulsarListenerEndpoint<?> endpoint, String[] propertyStrings) {
		if (propertyStrings.length > 0) {
			Properties properties = new Properties();
			for (String property : propertyStrings) {
				Object value = resolveExpression(property);
				if (value instanceof String) {
					loadProperty(properties, property, value);
				}
				else if (value instanceof String[]) {
					for (String prop : (String[]) value) {
						loadProperty(properties, prop, prop);
					}
				}
				else if (value instanceof Collection<?> values) {
					if (values.size() > 0 && values.iterator().next() instanceof String) {
						for (String prop : (Collection<String>) value) {
							loadProperty(properties, prop, prop);
						}
					}
				}
				else {
					throw new IllegalStateException("'properties' must resolve to a String, a String[] or "
							+ "Collection<String>");
				}
			}
			endpoint.setConsumerProperties(properties);
		}
	}

	private void loadProperty(Properties properties, String property, Object value) {
		try {
			properties.load(new StringReader((String) value));
		}
		catch (IOException e) {
			this.logger.error(e, () -> "Failed to load property " + property + ", continuing...");
		}
	}

	private String getEndpointSubscriptionName(PulsarListener pulsarListener) {
		if (StringUtils.hasText(pulsarListener.subscriptionName())) {
			return resolveExpressionAsString(pulsarListener.subscriptionName(), "subscriptionName");
		}
		else {
			return GENERATED_ID_PREFIX + this.counter.getAndIncrement();
		}
	}

	private SubscriptionType getEndpointSubscriptionType(PulsarListener pulsarListener) {
		final String subscriptionType = pulsarListener.subscriptionType().toLowerCase();
		if (StringUtils.hasText(subscriptionType)) {
			return switch (subscriptionType) {
				case "exclusive" -> SubscriptionType.Exclusive;
				case "failover" -> SubscriptionType.Failover;
				case "shared" -> SubscriptionType.Shared;
				case "key_shared" -> SubscriptionType.Key_Shared;
				default -> SubscriptionType.Exclusive;
			};
		}
		return null;
	}

	private String getEndpointId(PulsarListener pulsarListener) {
		if (StringUtils.hasText(pulsarListener.id())) {
			return resolveExpressionAsString(pulsarListener.id(), "id");
		}
		else {
			return GENERATED_ID_PREFIX + this.counter.getAndIncrement();
		}
	}

	private String resolveExpressionAsString(String value, String attribute) {
		Object resolved = resolveExpression(value);
		if (resolved instanceof String) {
			return (String) resolved;
		}
		else if (resolved != null) {
			throw new IllegalStateException(THE_LEFT + attribute + "] must resolve to a String. "
					+ RESOLVED_TO_LEFT + resolved.getClass() + RIGHT_FOR_LEFT + value + "]");
		}
		return null;
	}

	private String[] resolveTopics(PulsarListener PulsarListener) {
		String[] topics = PulsarListener.topics();
		List<String> result = new ArrayList<>();
		if (topics.length > 0) {
			for (String topic1 : topics) {
				Object topic = resolveExpression(topic1);
				resolveAsString(topic, result);
			}
		}
		return result.toArray(new String[0]);
	}

	private Object resolveExpression(String value) {
		return this.resolver.evaluate(resolve(value), this.expressionContext);
	}

	private String resolve(String value) {
		if (this.beanFactory != null && this.beanFactory instanceof ConfigurableBeanFactory) {
			return ((ConfigurableBeanFactory) this.beanFactory).resolveEmbeddedValue(value);
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	private void resolveAsString(Object resolvedValue, List<String> result) {
		if (resolvedValue instanceof String[]) {
			for (Object object : (String[]) resolvedValue) {
				resolveAsString(object, result);
			}
		}
		else if (resolvedValue instanceof String) {
			result.add((String) resolvedValue);
		}
		else if (resolvedValue instanceof Iterable) {
			for (Object object : (Iterable<Object>) resolvedValue) {
				resolveAsString(object, result);
			}
		}
		else {
			throw new IllegalArgumentException(String.format(
					"@PulsarListener can't resolve '%s' as a String", resolvedValue));
		}
	}

	private Method checkProxy(Method methodArg, Object bean) {
		Method method = methodArg;
		if (AopUtils.isJdkDynamicProxy(bean)) {
			try {
				// Found a @PulsarListener method on the target class for this JDK proxy ->
				// is it also present on the proxy itself?
				method = bean.getClass().getMethod(method.getName(), method.getParameterTypes());
				Class<?>[] proxiedInterfaces = ((Advised) bean).getProxiedInterfaces();
				for (Class<?> iface : proxiedInterfaces) {
					try {
						method = iface.getMethod(method.getName(), method.getParameterTypes());
						break;
					}
					catch (@SuppressWarnings("unused") NoSuchMethodException noMethod) {
						// NOSONAR
					}
				}
			}
			catch (SecurityException ex) {
				ReflectionUtils.handleReflectionException(ex);
			}
			catch (NoSuchMethodException ex) {
				throw new IllegalStateException(String.format(
						"@PulsarListener method '%s' found on bean target class '%s', " +
								"but not found in any interface(s) for bean JDK proxy. Either " +
								"pull the method up to an interface or switch to subclass (CGLIB) " +
								"proxies by setting proxy-target-class/proxyTargetClass " +
								"attribute to 'true'", method.getName(),
						method.getDeclaringClass().getSimpleName()), ex);
			}
		}
		return method;
	}

	private Collection<PulsarListener> findListenerAnnotations(Class<?> clazz) {
		Set<PulsarListener> listeners = new HashSet<>();
		PulsarListener ann = AnnotatedElementUtils.findMergedAnnotation(clazz, PulsarListener.class);
		if (ann != null) {
			ann = enhance(clazz, ann);
			listeners.add(ann);
		}
		PulsarListeners anns = AnnotationUtils.findAnnotation(clazz, PulsarListeners.class);
		if (anns != null) {
			listeners.addAll(Arrays.stream(anns.value())
					.map(anno -> enhance(clazz, anno))
					.collect(Collectors.toList()));
		}
		return listeners;
	}

	private Set<PulsarListener> findListenerAnnotations(Method method) {
		Set<PulsarListener> listeners = new HashSet<>();
		PulsarListener ann = AnnotatedElementUtils.findMergedAnnotation(method, PulsarListener.class);
		if (ann != null) {
			ann = enhance(method, ann);
			listeners.add(ann);
		}
		PulsarListeners anns = AnnotationUtils.findAnnotation(method, PulsarListeners.class);
		if (anns != null) {
			listeners.addAll(Arrays.stream(anns.value())
					.map(anno -> enhance(method, anno))
					.collect(Collectors.toList()));
		}
		return listeners;
	}

	private PulsarListener enhance(AnnotatedElement element, PulsarListener ann) {
		if (this.enhancer == null) {
			return ann;
		}
		else {
			return AnnotationUtils.synthesizeAnnotation(
					this.enhancer.apply(AnnotationUtils.getAnnotationAttributes(ann), element), PulsarListener.class, null);
		}
	}


	private void addFormatters(FormatterRegistry registry) {
		for (Converter<?, ?> converter : getBeansOfType(Converter.class)) {
			registry.addConverter(converter);
		}
		for (GenericConverter converter : getBeansOfType(GenericConverter.class)) {
			registry.addConverter(converter);
		}
		for (Formatter<?> formatter : getBeansOfType(Formatter.class)) {
			registry.addFormatter(formatter);
		}
	}

	private <T> Collection<T> getBeansOfType(Class<T> type) {
		if (PulsarListenerAnnotationBeanPostProcessor.this.beanFactory instanceof ListableBeanFactory) {
			return ((ListableBeanFactory) PulsarListenerAnnotationBeanPostProcessor.this.beanFactory)
					.getBeansOfType(type)
					.values();
		}
		else {
			return Collections.emptySet();
		}
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
		if (applicationContext instanceof ConfigurableApplicationContext) {
			setBeanFactory(((ConfigurableApplicationContext) applicationContext).getBeanFactory());
		}
		else {
			setBeanFactory(applicationContext);
		}
	}

	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		if (beanFactory instanceof ConfigurableListableBeanFactory) {
			this.resolver = ((ConfigurableListableBeanFactory) beanFactory).getBeanExpressionResolver();
			this.expressionContext = new BeanExpressionContext((ConfigurableListableBeanFactory) beanFactory,
					this.listenerScope);
		}
	}

	private class PulsarHandlerMethodFactoryAdapter implements MessageHandlerMethodFactory {

		private final DefaultFormattingConversionService defaultFormattingConversionService =
				new DefaultFormattingConversionService();

		private MessageHandlerMethodFactory handlerMethodFactory;

		public void setHandlerMethodFactory(MessageHandlerMethodFactory pulsarHandlerMethodFactory1) {
			this.handlerMethodFactory = pulsarHandlerMethodFactory1;
		}

		@Override
		public InvocableHandlerMethod createInvocableHandlerMethod(Object bean, Method method) {
			return getHandlerMethodFactory().createInvocableHandlerMethod(bean, method);
		}

		private MessageHandlerMethodFactory getHandlerMethodFactory() {
			if (this.handlerMethodFactory == null) {
				this.handlerMethodFactory = createDefaultMessageHandlerMethodFactory();
			}
			return this.handlerMethodFactory;
		}

		private MessageHandlerMethodFactory createDefaultMessageHandlerMethodFactory() {
			DefaultMessageHandlerMethodFactory defaultFactory = new DefaultMessageHandlerMethodFactory();
			Validator validator = PulsarListenerAnnotationBeanPostProcessor.this.registrar.getValidator();
			if (validator != null) {
				defaultFactory.setValidator(validator);
			}
			defaultFactory.setBeanFactory(PulsarListenerAnnotationBeanPostProcessor.this.beanFactory);
			this.defaultFormattingConversionService.addConverter(
					new BytesToStringConverter(PulsarListenerAnnotationBeanPostProcessor.this.charset));
			this.defaultFormattingConversionService.addConverter(new BytesToNumberConverter());
			defaultFactory.setConversionService(this.defaultFormattingConversionService);
			GenericMessageConverter messageConverter = new GenericMessageConverter(this.defaultFormattingConversionService);
			defaultFactory.setMessageConverter(messageConverter);

			List<HandlerMethodArgumentResolver> customArgumentsResolver =
					new ArrayList<>(PulsarListenerAnnotationBeanPostProcessor.this.registrar.getCustomMethodArgumentResolvers());
			// Has to be at the end - look at PayloadMethodArgumentResolver documentation
			//customArgumentsResolver.add(new PulsarNullAwarePayloadArgumentResolver(messageConverter, validator));
			defaultFactory.setCustomArgumentResolvers(customArgumentsResolver);

			defaultFactory.afterPropertiesSet();

			return defaultFactory;
		}

	}

	private static class BytesToStringConverter implements Converter<byte[], String> {


		private final Charset charset;

		BytesToStringConverter(Charset charset) {
			this.charset = charset;
		}

		@Override
		public String convert(byte[] source) {
			return new String(source, this.charset);
		}

	}

	private final class BytesToNumberConverter implements ConditionalGenericConverter {

		BytesToNumberConverter() {
		}

		@Override
		@Nullable
		public Set<ConvertiblePair> getConvertibleTypes() {
			HashSet<ConvertiblePair> pairs = new HashSet<>();
			pairs.add(new ConvertiblePair(byte[].class, long.class));
			pairs.add(new ConvertiblePair(byte[].class, int.class));
			pairs.add(new ConvertiblePair(byte[].class, short.class));
			pairs.add(new ConvertiblePair(byte[].class, byte.class));
			pairs.add(new ConvertiblePair(byte[].class, Long.class));
			pairs.add(new ConvertiblePair(byte[].class, Integer.class));
			pairs.add(new ConvertiblePair(byte[].class, Short.class));
			pairs.add(new ConvertiblePair(byte[].class, Byte.class));
			return pairs;
		}

		@Override
		@Nullable
		public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			byte[] bytes = (byte[]) source;
			if (targetType.getType().equals(long.class) || targetType.getType().equals(Long.class)) {
				Assert.state(bytes.length >= 8, "At least 8 bytes needed to convert a byte[] to a long"); // NOSONAR
				return ByteBuffer.wrap(bytes).getLong();
			}
			else if (targetType.getType().equals(int.class) || targetType.getType().equals(Integer.class)) {
				Assert.state(bytes.length >= 4, "At least 4 bytes needed to convert a byte[] to an integer"); // NOSONAR
				return ByteBuffer.wrap(bytes).getInt();
			}
			else if (targetType.getType().equals(short.class) || targetType.getType().equals(Short.class)) {
				Assert.state(bytes.length >= 2, "At least 2 bytes needed to convert a byte[] to a short");
				return ByteBuffer.wrap(bytes).getShort();
			}
			else if (targetType.getType().equals(byte.class) || targetType.getType().equals(Byte.class)) {
				Assert.state(bytes.length >= 1, "At least 1 byte needed to convert a byte[] to a byte");
				return ByteBuffer.wrap(bytes).get();
			}
			return null;
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			if (sourceType.getType().equals(byte[].class)) {
				Class<?> target = targetType.getType();
				return target.equals(long.class) || target.equals(int.class) || target.equals(short.class) // NOSONAR
						|| target.equals(byte.class) || target.equals(Long.class) || target.equals(Integer.class)
						|| target.equals(Short.class) || target.equals(Byte.class);
			}
			else {
				return false;
			}
		}

	}

	static class ListenerScope implements Scope {

		private final Map<String, Object> listeners = new HashMap<>();

		ListenerScope() {
		}

		public void addListener(String key, Object bean) {
			this.listeners.put(key, bean);
		}

		public void removeListener(String key) {
			this.listeners.remove(key);
		}

		@Override
		public Object get(String name, ObjectFactory<?> objectFactory) {
			return this.listeners.get(name);
		}

		@Override
		public Object remove(String name) {
			return null;
		}

		@Override
		public void registerDestructionCallback(String name, Runnable callback) {
		}

		@Override
		public Object resolveContextualObject(String key) {
			return this.listeners.get(key);
		}

		@Override
		public String getConversationId() {
			return null;
		}

	}


	public interface AnnotationEnhancer extends BiFunction<Map<String, Object>, AnnotatedElement, Map<String, Object>> {

	}


}


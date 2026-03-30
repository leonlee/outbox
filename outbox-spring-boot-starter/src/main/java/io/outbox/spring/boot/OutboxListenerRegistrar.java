package io.outbox.spring.boot;

import io.outbox.AggregateType;
import io.outbox.BoundEventListener;
import io.outbox.EventListener;
import io.outbox.EventType;
import io.outbox.registry.DefaultListenerRegistry;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scans for beans annotated with {@link OutboxListener} and registers them
 * in the {@link DefaultListenerRegistry}.
 *
 * <p>Runs after all singleton beans are initialized via {@link SmartInitializingSingleton}.
 * Beans that are both annotated with {@link OutboxListener} and implement
 * {@link BoundEventListener} are registered only once (via the annotation path).
 *
 * @see OutboxListener
 */
public class OutboxListenerRegistrar implements SmartInitializingSingleton {

    private final ListableBeanFactory beanFactory;
    private final DefaultListenerRegistry registry;
    private final Set<Object> registeredBeans = new HashSet<>();

    public OutboxListenerRegistrar(ListableBeanFactory beanFactory, DefaultListenerRegistry registry) {
        this.beanFactory = beanFactory;
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        processAnnotatedListeners();
        processBoundListeners();
    }

    private void processBoundListeners() {
        var listenerMap = beanFactory.getBeansOfType(BoundEventListener.class);
        for (var listener : listenerMap.values()) {
            if (registeredBeans.contains(listener)) {
                continue;
            }
            registry.register(listener);
        }
    }

    private void processAnnotatedListeners() {
        Map<String, Object> beans = beanFactory.getBeansWithAnnotation(OutboxListener.class);
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();

            if (!(bean instanceof EventListener listener)) {
                throw new BeanCreationException(beanName,
                        "Bean annotated with @OutboxListener must implement EventListener, " +
                                "but " + bean.getClass().getName() + " does not");
            }

            OutboxListener annotation = bean.getClass().getAnnotation(OutboxListener.class);
            if (annotation == null) {
                // Proxy may hide annotation; try the target class
                annotation = AnnotationUtils.findAnnotation(bean.getClass(), OutboxListener.class);
            }
            if (annotation == null) {
                throw new BeanCreationException(beanName,
                        "Could not find @OutboxListener annotation on " + bean.getClass().getName());
            }

            String eventTypeName = resolveEventType(beanName, annotation);
            String aggregateTypeName = resolveAggregateType(beanName, annotation);

            registry.register(aggregateTypeName, eventTypeName, listener);
            registeredBeans.add(bean);
        }
    }

    private String resolveEventType(String beanName, OutboxListener annotation) {
        Class<? extends EventType> eventTypeClass = annotation.eventTypeClass();
        if (eventTypeClass != EventType.class) {
            return instantiateAndGetName(beanName, eventTypeClass, "eventTypeClass");
        }
        String eventType = annotation.eventType();
        if (eventType.isEmpty()) {
            throw new BeanCreationException(beanName,
                    "@OutboxListener must specify either eventType or eventTypeClass");
        }
        return eventType;
    }

    private String resolveAggregateType(String beanName, OutboxListener annotation) {
        Class<? extends AggregateType> aggregateTypeClass = annotation.aggregateTypeClass();
        if (aggregateTypeClass != AggregateType.class) {
            return instantiateAndGetName(beanName, aggregateTypeClass, "aggregateTypeClass");
        }
        return annotation.aggregateType();
    }

    @SuppressWarnings("unchecked")
    private <T> String instantiateAndGetName(String beanName, Class<? extends T> clazz, String attrName) {
        try {
            if (clazz.isEnum()) {
                T[] constants = (T[]) clazz.getEnumConstants();
                if (constants == null || constants.length == 0) {
                    throw new BeanCreationException(beanName,
                            "@OutboxListener " + attrName + " enum " + clazz.getName() + " has no constants");
                }
                return callName(constants[0]);
            }
            T instance = clazz.getDeclaredConstructor().newInstance();
            return callName(instance);
        } catch (BeanCreationException e) {
            throw e;
        } catch (Exception e) {
            throw new BeanCreationException(beanName,
                    "Failed to instantiate @OutboxListener " + attrName + ": " + clazz.getName(), e);
        }
    }

    private String callName(Object instance) {
        if (instance instanceof EventType et) {
            return et.name();
        }
        if (instance instanceof AggregateType at) {
            return at.name();
        }
        throw new IllegalStateException("Unexpected type: " + instance.getClass());
    }
}

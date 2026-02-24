package outbox.spring.boot;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import outbox.AggregateType;
import outbox.EventListener;
import outbox.EventType;
import outbox.registry.DefaultListenerRegistry;

import java.util.Map;

/**
 * Scans for beans annotated with {@link OutboxListener} and registers them
 * in the {@link DefaultListenerRegistry}.
 *
 * <p>Runs after all singleton beans are initialized via {@link SmartInitializingSingleton}.
 *
 * @see OutboxListener
 */
public class OutboxListenerRegistrar implements SmartInitializingSingleton {

    private final ListableBeanFactory beanFactory;
    private final DefaultListenerRegistry registry;

    public OutboxListenerRegistrar(ListableBeanFactory beanFactory, DefaultListenerRegistry registry) {
        this.beanFactory = beanFactory;
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
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
                annotation = org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                        bean.getClass(), OutboxListener.class);
            }
            if (annotation == null) {
                throw new BeanCreationException(beanName,
                        "Could not find @OutboxListener annotation on " + bean.getClass().getName());
            }

            String eventTypeName = resolveEventType(beanName, annotation);
            String aggregateTypeName = resolveAggregateType(beanName, annotation);

            registry.register(aggregateTypeName, eventTypeName, listener);
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

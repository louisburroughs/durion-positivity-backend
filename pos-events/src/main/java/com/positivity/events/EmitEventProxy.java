package com.positivity.events;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 
 * This class creates JDK dynamic proxies that wrap target objects and intercept
 * method calls to track event execution timing and lifecycle. When a method
 * execution duration, and completion status (success or error), and publishes
 * an {@link EventEmitted} domain event.
 * 
 * The proxy provides observability for event-driven operations by capturing:
 * - Event start timestamp
 * - Event end timestamp
 * - Event execution errors
 * - Domain event publication
 * 
 * This is a complementary approach to {@link EmitEventAspect} for scenarios
 * where aspect-oriented programming is not available or proxy-based
 * interception is preferred.
 * 
 * Usage example:
 * 
 * <pre>
 * &#64;Service
 * public class MyServiceFactory {
 *     private final ApplicationEventPublisher publisher;
 * 
 *     public MyService createProxy(MyService target) {
 *         return EmitEventProxy.createProxy(target, MyService.class, publisher);
 *     }
 * }
 * </pre>
 * 
 * @see EmitEvent
 * @see EmitEventAspect
 * @see EventEmitted
 */
@Slf4j
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class EmitEventProxy {

    /**
     * 
     * @param <T>           the interface type
     * @param target        the target object to proxy
     * @param interfaceType the interface class
     * @param publisher     the Spring ApplicationEventPublisher for publishing
     *                      events
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target, Class<T> interfaceType, ApplicationEventPublisher publisher) {
        return (T) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[] { interfaceType },
                new EmitEventInvocationHandler(target, publisher));
    }

    private static class EmitEventInvocationHandler implements InvocationHandler {
        private final Object target;
        private final ApplicationEventPublisher publisher;

        EmitEventInvocationHandler(Object target, ApplicationEventPublisher publisher) {
            this.target = target;
            this.publisher = publisher;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            EmitEvent annotation = method.getAnnotation(EmitEvent.class);
            if (annotation != null) {
                String id = annotation.id();
                long start = Instant.now().toEpochMilli();
                log.info("[EVENT-START] id={} timestamp={}", id, start);
                try {
                    Object result = method.invoke(target, args);
                    long end = Instant.now().toEpochMilli();
                    log.info("[EVENT-END] id={} timestamp={}", id, end);

                    // Publish domain event to Event Receiver API
                    EventEmitted event = EventEmitted.from(id, end);
                    publisher.publishEvent(event);
                    log.debug("[EVENT-PUBLISHED] id={} to Event Receiver API", id);

                    return result;
                } catch (Exception e) {
                    long end = Instant.now().toEpochMilli();
                    log.error("[EVENT-ERROR] id={} timestamp={} error={}", id, end, e.getMessage());
                    throw e;
                }
            } else {
                return method.invoke(target, args);
            }
        }
    }
}

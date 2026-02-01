package com.positivity.events;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory for creating JDK dynamic proxies that intercept method calls
 * and emit domain events for methods annotated with {@link EmitEvent}.
 * 
 * This class creates proxies that wrap target objects and delegate event
 * emission logic to {@link EventEmissionService} for consistent behavior
 * across all event emission mechanisms.
 * 
 * The proxy provides observability for event-driven operations by capturing:
 * <ul>
 * <li>Event start timestamp</li>
 * <li>Event end timestamp</li>
 * <li>Event execution errors</li>
 * <li>Domain event publication</li>
 * </ul>
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
 *     private final EventEmissionService eventEmissionService;
 * 
 *     public MyService createProxy(MyService target) {
 *         return EmitEventProxy.createProxy(target, MyService.class, eventEmissionService);
 *     }
 * }
 * </pre>
 * 
 * @see EmitEvent
 * @see EmitEventAspect
 * @see EventEmitted
 * @see EventEmissionService
 */
@Slf4j
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class EmitEventProxy {

    /**
     * Creates a proxy that intercepts method calls and emits events for
     * methods annotated with {@link EmitEvent}.
     * 
     * @param <T>                  the interface type
     * @param target               the target object to proxy
     * @param interfaceType        the interface class
     * @param eventEmissionService the service for event emission logic
     * @return a proxy instance that wraps the target
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target, Class<T> interfaceType, EventEmissionService eventEmissionService) {
        return (T) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[] { interfaceType },
                new EmitEventInvocationHandler(target, eventEmissionService));
    }

    private static class EmitEventInvocationHandler implements InvocationHandler {
        private final Object target;
        private final EventEmissionService eventEmissionService;

        EmitEventInvocationHandler(Object target, EventEmissionService eventEmissionService) {
            this.target = target;
            this.eventEmissionService = eventEmissionService;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            EmitEvent annotation = method.getAnnotation(EmitEvent.class);
            if (annotation != null) {
                String eventId = annotation.id();
                String apiVersion = annotation.apiVersion();
                return eventEmissionService.executeWithEventEmission(eventId, apiVersion,
                        () -> method.invoke(target, args));
            } else {
                return method.invoke(target, args);
            }
        }
    }
}

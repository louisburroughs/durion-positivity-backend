package com.positivity.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Spring-managed factory for creating event-emitting dynamic proxies.
 * 
 * This factory provides a convenient way to create {@link EmitEventProxy}
 * instances
 * with automatic injection of the {@link ApplicationEventPublisher}. By using
 * this
 * factory, consumers don't need to manually wire the publisher.
 * 
 * The factory delegates to {@link EmitEventProxy#createProxy} while managing
 * the
 * publisher dependency through Spring's dependency injection.
 * 
 * Usage example:
 * 
 * <pre>
 * &#64;Service
 * public class OrderService {
 *     private final EmitEventProxyFactory proxyFactory;
 *     private final OrderRepository orderRepository;
 * 
 *     public OrderService(EmitEventProxyFactory proxyFactory, OrderRepository orderRepository) {
 *         this.proxyFactory = proxyFactory;
 *         this.orderRepository = orderRepository;
 *     }
 * 
 *     public OrderProcessor createOrderProcessor() {
 *         OrderProcessorImpl impl = new OrderProcessorImpl(orderRepository);
 *         return proxyFactory.createProxy(impl, OrderProcessor.class);
 *     }
 * }
 * </pre>
 * 
 * @see EmitEventProxy
 * @see EmitEvent
 * @see EmitEventAspect
 */
@Component
@RequiredArgsConstructor
public class EmitEventProxyFactory {

    /** Application event publisher injected by Spring */
    private final ApplicationEventPublisher publisher;

    /**
     * Creates a dynamic proxy that intercepts @EmitEvent annotated methods.
     * 
     * This method wraps {@link EmitEventProxy#createProxy} and automatically
     * provides the Spring-managed {@link ApplicationEventPublisher}.
     * 
     * @param <T>           the interface type
     * @param target        the target object to proxy
     * @param interfaceType the interface class that the proxy will implement
     * @return a proxy instance that intercepts @EmitEvent methods and publishes
     *         events
     */
    public <T> T createProxy(T target, Class<T> interfaceType) {
        return EmitEventProxy.createProxy(target, interfaceType, publisher);
    }
}

package com.positivity.events;

import com.positivity.events.service.EventEmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Spring-managed factory for creating event-emitting dynamic proxies.
 *
 * This factory provides a convenient way to create {@link EmitEventProxy}
 * instances with automatic injection of the {@link EventEmissionService}.
 * By using this factory, consumers don't need to manually wire the service.
 *
 * The factory delegates to {@link EmitEventProxy#createProxy} while managing
 * the service dependency through Spring's dependency injection.
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
 * @see EventEmissionService
 */
@Component
@RequiredArgsConstructor
public class EmitEventProxyFactory {

    /** Event emission service injected by Spring */
    private final EventEmissionService eventEmissionService;

    /**
     * Creates a proxy for the given target that emits events for methods
     * annotated with {@link EmitEvent}.
     *
     * This method wraps {@link EmitEventProxy#createProxy} and automatically
     * provides the Spring-managed {@link EventEmissionService}.
     *
     * @param <T>           the interface type
     * @param target        the target object to proxy
     * @param interfaceType the interface class that the proxy will implement
     * @return a proxy that wraps the target and emits events
     */
    public <T> T createProxy(T target, Class<T> interfaceType) {
        return EmitEventProxy.createProxy(target, interfaceType, eventEmissionService);
    }
}

package com.positivity.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for methods that should emit domain events.
 *
 * When applied to a method, this annotation signals that the method performs
 * a significant business operation that should be captured as a domain event
 * for asynchronous consumption by other modules via Spring Modulith.
 *
 * publishes an {@link EventEmitted} domain event after the method completes
 * successfully, and enables decoupled, event-driven communication between
 * modules while maintaining audit trails and observability.
 *
 * Usage example:
 *
 * <pre>
 * public Order createOrder(OrderRequest request) {
 *     // business logic
 *     return savedOrder;
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EmitEvent {
    /**
     * Unique identifier for the event being emitted.
     * Used to classify and route events to appropriate listeners.
     */
    String id();

    /**
     * API version for the event.
     * Used to track which API version triggered the event.
     * Defaults to "1".
     */
    String apiVersion() default "1";
}

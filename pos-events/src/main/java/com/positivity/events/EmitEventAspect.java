package com.positivity.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;
import java.time.Instant;

/**
 * Aspect for intercepting and publishing domain events from methods annotated
 * 
 * This aspect uses Spring AOP to intercept methods marked with the
 * {@link EmitEvent} annotation,
 * providing automatic event publication and observability for event-driven
 * operations across
 * the backend microservices.
 * 
 * 1. Logs the event start timestamp
 * 2. Executes the target method
 * 3. Publishes an {@link EventEmitted} domain event via Spring's
 * ApplicationEventPublisher
 * 4. Logs the event completion timestamp
 * 5. Logs any errors that occur during method execution
 * 
 * The published EventEmitted events are consumed by the Event Receiver module
 * via
 * asynchronous
 * communication between modules while maintaining audit trails and
 * observability.
 * 
 * This aspect is the primary mechanism for event-driven intermodule
 * communication,
 * enforcing the architectural constraint that all cross-module interactions
 * must use
 * direct repository access or REST calls.
 * 
 * @see EmitEvent
 * @see EventEmitted
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class EmitEventAspect {
    /** Application event publisher for publishing domain events */
    private final ApplicationEventPublisher publisher;

    /**
     * 
     * @param joinPoint
     * @return
     * @throws Throwable
     */
    @SuppressWarnings("null")
    @Around("@annotation(com.positivity.events.EmitEvent)")
    public Object emitEvent(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        EmitEvent annotation = method.getAnnotation(EmitEvent.class);
        String id = annotation.id();
        long start = Instant.now().toEpochMilli();
        log.info("[EVENT-START] id={} timestamp={}", id, start);
        try {
            Object result = joinPoint.proceed();
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
    }
}

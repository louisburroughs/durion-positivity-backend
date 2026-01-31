package com.positivity.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;

/**
 * Aspect for intercepting and publishing domain events from methods annotated
 * with {@link EmitEvent}.
 * 
 * This aspect uses Spring AOP to intercept methods marked with the
 * {@link EmitEvent} annotation, delegating event emission logic to
 * {@link EventEmissionService} for consistent behavior across all
 * event emission mechanisms.
 * 
 * The aspect:
 * 1. Intercepts methods annotated with @EmitEvent
 * 2. Delegates to EventEmissionService for timing, logging, and event
 * publication
 * 3. Propagates any errors from the target method
 * 
 * The published EventEmitted events are consumed by the Event Receiver module
 * for asynchronous communication between modules while maintaining audit trails
 * and observability.
 * 
 * @see EmitEvent
 * @see EventEmitted
 * @see EventEmissionService
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class EmitEventAspect {

    private final EventEmissionService eventEmissionService;

    /**
     * Intercepts methods annotated with @EmitEvent and delegates to
     * EventEmissionService.
     * 
     * @param joinPoint the join point representing the intercepted method
     * @return the result of the target method
     * @throws Throwable if the target method throws an exception
     */
    @Around("@annotation(com.positivity.events.EmitEvent)")
    public Object emitEvent(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        EmitEvent annotation = method.getAnnotation(EmitEvent.class);
        String eventId = annotation.id();
        String apiVersion = annotation.apiVersion();

        return eventEmissionService.executeWithEventEmission(eventId, apiVersion, joinPoint::proceed);
    }
}

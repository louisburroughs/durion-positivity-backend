package com.positivity.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.modulith.events.DomainEventPublisher;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;
import java.time.Instant;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class EmitEventAspect {
    private final DomainEventPublisher publisher;

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

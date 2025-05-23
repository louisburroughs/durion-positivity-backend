package com.positivity.posevents;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;
import java.time.Instant;

@Slf4j
@Aspect
@Component
public class EmitEventAspect {
    @Around("@annotation(com.positivity.posevents.EmitEvent)")
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
            return result;
        } catch (Exception e) {
            long end = Instant.now().toEpochMilli();
            log.error("[EVENT-ERROR] id={} timestamp={} error={}", id, end, e.getMessage());
            throw e;
        }
    }
}

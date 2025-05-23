package com.positivity.posevents;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EmitEventProxy {
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target, Class<T> interfaceType) {
        return (T) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[]{interfaceType},
                new EmitEventInvocationHandler(target)
        );
    }

    private static class EmitEventInvocationHandler implements InvocationHandler {
        private final Object target;

        EmitEventInvocationHandler(Object target) {
            this.target = target;
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

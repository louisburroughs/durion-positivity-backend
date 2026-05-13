package com.positivity.bulkloader.internal.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class BulkLoadAuthorizationContext {

    private final ThreadLocal<String> authorizationHeaderHolder = new ThreadLocal<>();

    public void setAuthorizationHeader(@Nullable String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            authorizationHeaderHolder.remove();
            return;
        }
        authorizationHeaderHolder.set(authorizationHeader);
    }

    public @Nullable String getAuthorizationHeader() {
        return authorizationHeaderHolder.get();
    }

    public void clear() {
        authorizationHeaderHolder.remove();
    }
}

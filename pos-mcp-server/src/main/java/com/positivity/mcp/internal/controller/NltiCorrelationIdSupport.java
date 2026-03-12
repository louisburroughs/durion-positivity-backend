package com.positivity.mcp.internal.controller;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

final class NltiCorrelationIdSupport {

    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    static final String CORRELATION_ID_ATTRIBUTE =
            NltiCorrelationIdSupport.class.getName() + ".resolvedCorrelationId";

    private NltiCorrelationIdSupport() {
    }

    static @NonNull UUID resolveFromHeader(@Nullable String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            try {
                return UUID.fromString(headerValue);
            } catch (IllegalArgumentException ignored) {
                // fall through to generate
            }
        }
        return UUIDv7Generator.generate();
    }

    static @NonNull UUID resolveFromRequest(@NonNull HttpServletRequest request) {
        Object existing = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        if (existing instanceof UUID existingUuid) {
            return existingUuid;
        }

        UUID resolved = resolveFromHeader(request.getHeader(CORRELATION_ID_HEADER));
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, resolved);
        return resolved;
    }
}

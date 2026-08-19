package com.positivity.mcp.internal.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the SLF4J MDC with the request's correlation id for every inbound request, so telemetry
 * emitted deep in the orchestration layer ({@code SessionAgentManager.emitChatTelemetry} and
 * {@code StreamingSessionAgentManager.resolveCorrelationId} both read {@code MDC.get("correlationId")})
 * carries the caller-supplied {@code X-Correlation-Id} instead of a fresh random fallback id.
 *
 * <p>Before this filter existed nothing in the service ever called {@code MDC.put}, so chat telemetry
 * always fell back to a generated id and eval harnesses had to join telemetry to requests by
 * time-window — which live verification showed misattributes records across consecutive personas.
 *
 * <p>Resolution is delegated to {@link NltiCorrelationIdSupport#resolveFromRequest}: reuse a valid
 * {@code X-Correlation-Id} header UUID, otherwise generate a UUIDv7, and cache the result as a request
 * attribute. Because the filter caches under the same attribute key the NLTI controllers and exception
 * handlers already read, they find the filter's id and never mint a second one for the same request.
 *
 * <p>The effective id is echoed on the response before the chain runs (so it survives a committed or
 * streamed response), letting a caller that supplied no header still quote the id when reporting a
 * problem.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so it wraps the Spring Security filter chain
 * (registered at {@code SecurityProperties.DEFAULT_FILTER_ORDER}, i.e. -100): security handling — and
 * any 401/403 the entry point writes — happens inside the MDC scope and carries the echoed header.
 *
 * <p>Async (SSE) dispatches are intentionally not re-filtered (the {@link OncePerRequestFilter}
 * default): {@code StreamingSessionAgentManager} captures the MDC value on the request thread at
 * Flux-assembly time, so no reactive propagation is required.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdMdcFilter extends OncePerRequestFilter {

    /** MDC key read by the session agent managers when attributing telemetry. */
    static final String MDC_CORRELATION_ID_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        MDC.put(MDC_CORRELATION_ID_KEY, correlationId.toString());
        try {
            response.setHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString());
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CORRELATION_ID_KEY);
        }
    }
}

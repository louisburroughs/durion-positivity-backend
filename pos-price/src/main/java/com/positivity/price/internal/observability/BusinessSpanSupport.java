package com.positivity.price.internal.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Shared helpers for manual OTel business spans layered on top of the Grafana OTel Java Agent
 * (v2.9.0) auto-instrumentation already attached via {@code -javaagent}.
 *
 * <p>API-only: no {@code TracerProvider}/{@code SdkTracerProvider} is constructed here or
 * anywhere in this module. The agent owns SDK setup; this class only touches the OTel API.
 */
public final class BusinessSpanSupport {

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";
    public static final String OUTCOME_PARTIAL = "partial";

    private static final String TRACE_ID_MDC_KEY = "trace_id";
    private static final String SPAN_ID_MDC_KEY = "span_id";

    private BusinessSpanSupport() {}

    /** Record a terminal failure on the given span: ERROR status + exception event. */
    public static void recordFailure(Span span, Throwable error) {
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        span.setStatus(StatusCode.ERROR, message);
        span.recordException(error);
        span.setAttribute("app.operation.outcome", OUTCOME_FAILURE);
    }

    /**
     * Log at INFO with {@code trace_id}/{@code span_id} MDC correlation fields, guarded so logs
     * emitted with no active/valid span context never carry fabricated zero-value IDs.
     */
    public static void logWithTraceContext(Logger logger, String message, Object... args) {
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            MDC.put(TRACE_ID_MDC_KEY, span.getSpanContext().getTraceId());
            MDC.put(SPAN_ID_MDC_KEY, span.getSpanContext().getSpanId());
            try {
                logger.info(message, args);
            } finally {
                MDC.remove(TRACE_ID_MDC_KEY);
                MDC.remove(SPAN_ID_MDC_KEY);
            }
        } else {
            logger.info(message, args);
        }
    }
}

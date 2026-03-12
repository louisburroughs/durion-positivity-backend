package com.positivity.mcp.internal.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies OTel span attribute constant keys declared in
 * {@link NltiSpanAttributes}.
 *
 * <p>
 * Tests AC5 of NLTI-009: the constants {@code correlationId},
 * {@code requestId},
 * {@code userId}, and {@code sessionId} must be present with the correct
 * dotted-key
 * values so that instrumentation code can reference them without magic strings.
 *
 * <p>
 * These tests pass immediately because the scaffold constants are already in
 * place.
 * They provide regression protection against accidental key renaming.
 *
 * Issue: NLTI-009
 */
class NltiTracingTest {

    @Test
    @DisplayName("NltiSpanAttributes.NLT_CORRELATION_ID == 'nlt.correlationId'")
    void nltiSpanAttributes_hasCorrelationIdKey() {
        assertThat(NltiSpanAttributes.NLT_CORRELATION_ID).isEqualTo("nlt.correlationId");
    }

    @Test
    @DisplayName("NltiSpanAttributes.NLT_REQUEST_ID == 'nlt.requestId'")
    void nltiSpanAttributes_hasRequestIdKey() {
        assertThat(NltiSpanAttributes.NLT_REQUEST_ID).isEqualTo("nlt.requestId");
    }

    @Test
    @DisplayName("NltiSpanAttributes.NLT_USER_ID == 'nlt.userId'")
    void nltiSpanAttributes_hasUserIdKey() {
        assertThat(NltiSpanAttributes.NLT_USER_ID).isEqualTo("nlt.userId");
    }

    @Test
    @DisplayName("NltiSpanAttributes.NLT_SESSION_ID == 'nlt.sessionId'")
    void nltiSpanAttributes_hasSessionIdKey() {
        assertThat(NltiSpanAttributes.NLT_SESSION_ID).isEqualTo("nlt.sessionId");
    }
}

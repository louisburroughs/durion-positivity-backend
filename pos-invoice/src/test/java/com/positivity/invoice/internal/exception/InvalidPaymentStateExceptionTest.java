package com.positivity.invoice.internal.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InvalidPaymentStateException}.
 *
 * <p>
 * Covers both constructors to ensure the exception carries the correct
 * message and cause through the exception hierarchy.
 *
 * Story #9.
 */
class InvalidPaymentStateExceptionTest {

    @Test
    void constructor_withMessage_storesMessage() {
        var ex = new InvalidPaymentStateException("invalid state");

        assertThat(ex.getMessage()).isEqualTo("invalid state");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void constructor_withMessageAndCause_storesMessageAndCause() {
        var cause = new IllegalStateException("root cause");
        var ex = new InvalidPaymentStateException("wrapping message", cause);

        assertThat(ex.getMessage()).isEqualTo("wrapping message");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}

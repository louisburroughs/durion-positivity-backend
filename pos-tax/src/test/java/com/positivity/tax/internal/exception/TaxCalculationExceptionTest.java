package com.positivity.tax.internal.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaxCalculationException Tests")
class TaxCalculationExceptionTest {

    @Test
    @DisplayName("Should create exception with message only")
    void shouldCreateExceptionWithMessageOnly() {
        // When
        TaxCalculationException exception = new TaxCalculationException("tax calculation failed");

        // Then
        assertThat(exception.getMessage()).isEqualTo("tax calculation failed");
        assertThat(exception.getCause()).isNull();
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Should create exception with message and cause")
    void shouldCreateExceptionWithMessageAndCause() {
        // Given
        RuntimeException cause = new RuntimeException("upstream failure");

        // When
        TaxCalculationException exception = new TaxCalculationException("tax calculation failed", cause);

        // Then
        assertThat(exception.getMessage()).isEqualTo("tax calculation failed");
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Should preserve cause message when wrapped")
    void shouldPreserveCauseMessageWhenWrapped() {
        // Given
        IllegalStateException cause = new IllegalStateException("invalid state");

        // When
        TaxCalculationException exception = new TaxCalculationException("wrapper", cause);

        // Then
        assertThat(exception.getCause().getMessage()).isEqualTo("invalid state");
    }
}

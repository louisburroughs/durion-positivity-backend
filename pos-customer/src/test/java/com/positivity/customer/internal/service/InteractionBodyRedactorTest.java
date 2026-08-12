package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story #1141: interaction bodies are human-typed, so they accumulate contact
 * details.
 */
class InteractionBodyRedactorTest {

    @Test
    @DisplayName("masks an email dictated into a call note")
    void masksEmail() {
        assertThat(InteractionBodyRedactor.redact("Customer asked us to use jane@example.com instead"))
                .isEqualTo("Customer asked us to use [redacted-email] instead");
    }

    @Test
    @DisplayName("does not partially mask an email-like token with an overlong local part")
    void leavesOverlongEmailLikeTokenAlone() {
        String overlongAddress = "a".repeat(65) + "@example.com";

        assertThat(InteractionBodyRedactor.redact(overlongAddress)).isEqualTo(overlongAddress);
    }

    @Test
    @DisplayName("masks a long digit run that could be a phone or card number")
    void masksLongDigitRun() {
        assertThat(InteractionBodyRedactor.redact("Call back on 555-010-0100"))
                .isEqualTo("Call back on [redacted-number]");
    }

    @Test
    @DisplayName("leaves short numbers alone — a mileage or part number is not a phone number")
    void leavesShortNumbersAlone() {
        assertThat(InteractionBodyRedactor.redact("Odometer 84210 at drop-off, quoted 249.99"))
                .isEqualTo("Odometer 84210 at drop-off, quoted 249.99");
    }

    @Test
    @DisplayName("null and blank bodies pass through unchanged")
    void passesThroughEmptyBodies() {
        assertThat(InteractionBodyRedactor.redact(null)).isNull();
        assertThat(InteractionBodyRedactor.redact("")).isEmpty();
    }

    @Test
    @DisplayName("flags text that redaction would change")
    void detectsRedactableContent() {
        assertThat(InteractionBodyRedactor.containsRedactableContent("reach me at jane@example.com"))
                .isTrue();
        assertThat(InteractionBodyRedactor.containsRedactableContent("rotate tires next visit"))
                .isFalse();
    }
}

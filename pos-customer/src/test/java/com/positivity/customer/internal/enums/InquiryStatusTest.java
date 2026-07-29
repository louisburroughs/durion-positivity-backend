package com.positivity.customer.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Story #1154: inquiry lifecycle. */
class InquiryStatusTest {

    @Test
    @DisplayName("a new inquiry can be contacted, converted, or closed")
    void newTransitions() {
        assertThat(InquiryStatus.NEW.canTransitionTo(InquiryStatus.CONTACTED)).isTrue();
        assertThat(InquiryStatus.NEW.canTransitionTo(InquiryStatus.CONVERTED)).isTrue();
        assertThat(InquiryStatus.NEW.canTransitionTo(InquiryStatus.CLOSED)).isTrue();
    }

    @Test
    @DisplayName("a contacted inquiry cannot go back to NEW")
    void contactedCannotRegress() {
        assertThat(InquiryStatus.CONTACTED.canTransitionTo(InquiryStatus.NEW)).isFalse();
    }

    @Test
    @DisplayName("converted and closed are terminal")
    void terminalStates() {
        for (InquiryStatus target : InquiryStatus.values()) {
            assertThat(InquiryStatus.CONVERTED.canTransitionTo(target)).isFalse();
            assertThat(InquiryStatus.CLOSED.canTransitionTo(target)).isFalse();
        }
    }
}

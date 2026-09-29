package com.positivity.accounting.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which failure reasons end terminal ({@code SKIPPED}) and which suspensions the scheduled
 * auto-retry loop leaves for an operator (ADR-0067 PC-9, issue #2334).
 */
class PostingFailureReasonTest {

    @Test
    @DisplayName("A currency hold is not a terminal skip: it is SUSPENDED and releasable (#2334)")
    void currencyNotSupportedIsNotTerminal() {
        assertThat(PostingFailureReason.CURRENCY_NOT_SUPPORTED.isTerminalSkip()).isFalse();
    }

    @Test
    @DisplayName("Only the facts that post nothing by design are terminal skips")
    void terminalSkips() {
        assertThat(Arrays.stream(PostingFailureReason.values()).filter(PostingFailureReason::isTerminalSkip))
                .containsExactlyInAnyOrder(
                        PostingFailureReason.UNCOSTED_FACT,
                        PostingFailureReason.MISSING_AMOUNT,
                        PostingFailureReason.ZERO_AMOUNT);
    }

    @Test
    @DisplayName("The auto-retry loop leaves PERIOD_CLOSED and CURRENCY_NOT_SUPPORTED suspensions to an operator")
    void excludedFromAutoRetry() {
        EnumSet<PostingFailureReason> excluded = EnumSet.noneOf(PostingFailureReason.class);
        for (PostingFailureReason reason : PostingFailureReason.values()) {
            if (reason.isExcludedFromAutoRetry()) {
                excluded.add(reason);
            }
        }
        assertThat(excluded)
                .containsExactlyInAnyOrder(
                        PostingFailureReason.PERIOD_CLOSED, PostingFailureReason.CURRENCY_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("A stored failure reason code is excluded from auto-retry by name; unknown or absent codes are not")
    void excludedFromAutoRetryByCode() {
        assertThat(PostingFailureReason.isExcludedFromAutoRetry("CURRENCY_NOT_SUPPORTED"))
                .isTrue();
        assertThat(PostingFailureReason.isExcludedFromAutoRetry("PERIOD_CLOSED"))
                .isTrue();
        assertThat(PostingFailureReason.isExcludedFromAutoRetry("UNMAPPED_EVENT_TYPE"))
                .isFalse();
        assertThat(PostingFailureReason.isExcludedFromAutoRetry("NOT_A_REASON")).isFalse();
        assertThat(PostingFailureReason.isExcludedFromAutoRetry(null)).isFalse();
    }
}

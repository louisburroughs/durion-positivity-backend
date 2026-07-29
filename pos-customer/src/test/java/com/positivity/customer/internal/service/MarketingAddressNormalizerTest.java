package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.customer.internal.enums.MarketingChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story #1140: normalization must be identical on the write and read paths, or a suppressed
 * address silently stops matching and the block quietly stops working.
 */
class MarketingAddressNormalizerTest {

    @Test
    @DisplayName("email hashing ignores case and surrounding whitespace")
    void emailHashIsCaseAndWhitespaceStable() {
        String canonical = MarketingAddressNormalizer.hash(MarketingChannel.EMAIL, "jane@example.com");

        assertThat(MarketingAddressNormalizer.hash(MarketingChannel.EMAIL, "  JANE@Example.COM "))
                .isEqualTo(canonical);
    }

    @Test
    @DisplayName("phone hashing ignores formatting so a dialled and stored number match")
    void phoneHashIgnoresFormatting() {
        String canonical = MarketingAddressNormalizer.hash(MarketingChannel.SMS, "5550100100");

        assertThat(MarketingAddressNormalizer.hash(MarketingChannel.SMS, "(555) 010-0100"))
                .isEqualTo(canonical);
    }

    @Test
    @DisplayName("an international prefix is a different address, not the same one")
    void internationalPrefixIsSignificant() {
        assertThat(MarketingAddressNormalizer.hash(MarketingChannel.SMS, "+15550100100"))
                .isNotEqualTo(MarketingAddressNormalizer.hash(MarketingChannel.SMS, "15550100100"));
    }

    @Test
    @DisplayName("the hint masks the address while staying recognisable")
    void hintMasksAddress() {
        assertThat(MarketingAddressNormalizer.hint(MarketingChannel.EMAIL, "jane@example.com"))
                .isEqualTo("j***@example.com");
        assertThat(MarketingAddressNormalizer.hint(MarketingChannel.SMS, "5550100100"))
                .isEqualTo("***0100");
    }

    @Test
    @DisplayName("a hash is 64 hex characters and never contains the raw address")
    void hashIsOpaque() {
        String hash = MarketingAddressNormalizer.hash(MarketingChannel.EMAIL, "jane@example.com");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").doesNotContain("jane");
    }

    @Test
    @DisplayName("blank and digitless input is rejected rather than hashed to a shared key")
    void rejectsUnusableInput() {
        assertThatThrownBy(() -> MarketingAddressNormalizer.hash(MarketingChannel.EMAIL, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketingAddressNormalizer.hash(MarketingChannel.SMS, "no-digits"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

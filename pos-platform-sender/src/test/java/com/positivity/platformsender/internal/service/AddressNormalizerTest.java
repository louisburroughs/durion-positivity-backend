package com.positivity.platformsender.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AddressNormalizer — deliverable forms and the suppression hash")
class AddressNormalizerTest {

    @Test
    @DisplayName("an email is trimmed and lowercased")
    void emailIsTrimmedAndLowercased() {
        assertThat(AddressNormalizer.email("  Ada.Lovelace@Example.COM ")).contains("ada.lovelace@example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-an-email", "two@@example.com", "no-domain@", "spaced out@example.com"})
    @DisplayName("a value that is not an email is not deliverable")
    void malformedEmailIsRejected(String stored) {
        assertThat(AddressNormalizer.email(stored)).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "'+1 (555) 010-0100', +15550100100",
        "'(555) 010-0100', +15550100100",
        "5550100100, +15550100100",
        "15550100100, +15550100100",
        "'+44 20 7946 0958', +442079460958",
    })
    @DisplayName("a phone number becomes E.164")
    void phoneBecomesE164(String stored, String expected) {
        assertThat(AddressNormalizer.e164(stored, "1")).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "call me",
                "555-0100",
                "025550100100",
                "+1234567",
                "+1234567890123456",
                "+00000000",
                "+1abc5550100100",
                "555 010 0100 ext 2"
            })
    @DisplayName("a number with no unambiguous E.164 form is not deliverable")
    void ambiguousPhoneIsRejected(String stored) {
        assertThat(AddressNormalizer.e164(stored, "1")).isEmpty();
    }

    @Test
    @DisplayName("a national number takes the configured country code")
    void nationalNumberTakesConfiguredCode() {
        assertThat(AddressNormalizer.e164("7946095812", "44")).contains("+447946095812");
    }

    @Test
    @DisplayName("the hash is SHA-256 lowercase hex of the UTF-8 address, as pos-customer computes it")
    void hashMatchesSuppressionHash() {
        // echo -n "ada@example.com" | sha256sum
        assertThat(AddressNormalizer.hash("ada@example.com"))
                .isEqualTo("b5fc85e55755f9e0d030a10ab4429b6b2944855f9a0d60077fe832becbc41d72");
        assertThat(AddressNormalizer.hash("+15550100100")).isNotEqualTo(AddressNormalizer.hash("5550100100"));
    }
}

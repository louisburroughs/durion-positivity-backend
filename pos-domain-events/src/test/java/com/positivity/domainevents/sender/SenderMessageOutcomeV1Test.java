package com.positivity.domainevents.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code SenderMessageOutcomeV1}'s wire shape is FI-2 §2: pos-marketing reads it field by field
 * ({@code PlatformSenderContractTest}), so the names, the ISO-8601 {@code occurredAt} and the
 * omission of absent fields are the contract.
 */
class SenderMessageOutcomeV1Test {

    private static final UUID MESSAGE_ID = UUID.fromString("01990000-0000-7000-8000-0000000000c1");
    private static final Instant AT = Instant.parse("2026-10-03T12:00:02Z");

    @Test
    @DisplayName("serializes with the contract's field names, an ISO instant and no nulls")
    void wireShape() {
        JsonNode json = new ObjectMapper()
                .valueToTree(new SenderMessageOutcomeV1(MESSAGE_ID, "EMAIL", "ses-1", AT, null, null, null));

        assertThat(json.path("providerMessageId").asString()).isEqualTo("ses-1");
        assertThat(json.path("occurredAt").asString()).isEqualTo("2026-10-03T12:00:02Z");
        assertThat(json.path("messageId").asString()).isEqualTo(MESSAGE_ID.toString());
        assertThat(json.has("permanent"))
                .as("absent permanent must not read as an explicit null")
                .isFalse();
        assertThat(json.has("address")).isFalse();
        assertThat(json.has("reason")).isFalse();
    }

    @Test
    @DisplayName("a hard bounce carries permanent and the address")
    void hardBounce() {
        JsonNode json = new ObjectMapper()
                .valueToTree(new SenderMessageOutcomeV1(
                        MESSAGE_ID, "SMS", "sms-1", AT, "TEXT_INVALID", true, "+15550100100"));

        assertThat(json.path("permanent").asBoolean()).isTrue();
        assertThat(json.path("address").asString()).isEqualTo("+15550100100");
    }

    @Test
    @DisplayName("the correlation fields are required")
    void guards() {
        assertThatThrownBy(() -> new SenderMessageOutcomeV1(null, "EMAIL", "ses-1", AT, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
        assertThatThrownBy(() -> new SenderMessageOutcomeV1(MESSAGE_ID, " ", "ses-1", AT, null, null, null))
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> new SenderMessageOutcomeV1(MESSAGE_ID, "EMAIL", "", AT, null, null, null))
                .hasMessageContaining("providerMessageId");
        assertThatThrownBy(() -> new SenderMessageOutcomeV1(MESSAGE_ID, "EMAIL", "ses-1", null, null, null, null))
                .hasMessageContaining("occurredAt");
    }
}

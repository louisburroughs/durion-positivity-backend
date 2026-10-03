package com.positivity.domainevents.sender;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: the provider reported what became of one message the platform sender accepted (FI-2,
 * {@code durion/domains/positivity/PLATFORM_SENDER_CONTRACT.md} §2).
 *
 * <p>Published by pos-platform-sender on {@code sender.outcomes.v1} under one of five event types,
 * one per contract row: {@link #EVENT_TYPE_DELIVERED}, {@link #EVENT_TYPE_BOUNCED},
 * {@link #EVENT_TYPE_COMPLAINED}, {@link #EVENT_TYPE_OPENED} and {@link #EVENT_TYPE_CLICKED}. The
 * payload shape is the same for all five so a consumer needs one deserializer. The envelope's
 * aggregateId is {@link #messageId}; the Kafka record key is {@link #providerMessageId}, which is
 * what the contract keys the topic by.
 *
 * <p>Absent values are left out of the JSON rather than written as {@code null}: the contract's
 * consumer reads a missing {@code permanent} as {@code true} and a missing {@code address} as "no
 * suppression hand-off", and an explicit {@code null} must not be mistaken for either.
 *
 * @param messageId the caller's idempotency key from the send request ({@code campaignSendId} for
 *     pos-marketing)
 * @param channel {@code EMAIL} or {@code SMS}
 * @param providerMessageId the id the send API returned on acceptance; the consumer's correlation key
 * @param occurredAt when the provider observed the outcome
 * @param reason the provider's reason for a bounce or complaint, when it gave one
 * @param permanent on a bounce, whether the address itself is bad (a hard bounce, which feeds CRM
 *     suppression) rather than the delivery attempt; absent on every other outcome
 * @param address the normalized address, on a bounce or complaint only, for the suppression
 *     hand-off; never persisted by the consumer
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SenderMessageOutcomeV1(
        @NonNull UUID messageId,
        @NonNull String channel,
        @NonNull String providerMessageId,
        @NonNull Instant occurredAt,
        @Nullable String reason,
        @Nullable Boolean permanent,
        @Nullable String address) {

    public static final String EVENT_TYPE_DELIVERED = "sender.message.delivered";
    public static final String EVENT_TYPE_BOUNCED = "sender.message.bounced";
    public static final String EVENT_TYPE_COMPLAINED = "sender.message.complained";
    public static final String EVENT_TYPE_OPENED = "sender.message.opened";
    public static final String EVENT_TYPE_CLICKED = "sender.message.clicked";
    public static final int SCHEMA_VERSION = 1;

    public SenderMessageOutcomeV1 {
        if (messageId == null) {
            throw new IllegalArgumentException("messageId must not be null");
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        if (providerMessageId == null || providerMessageId.isBlank()) {
            throw new IllegalArgumentException("providerMessageId must not be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}

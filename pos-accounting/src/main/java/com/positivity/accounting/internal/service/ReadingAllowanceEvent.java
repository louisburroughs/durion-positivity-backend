package com.positivity.accounting.internal.service;

import com.positivity.domainevents.tenant.TenantEventTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import tools.jackson.databind.JsonNode;

/**
 * One {@code tenant.reading-allowance.changed} fact as the replica needs it (CAP:550 S45, #2619): the envelope
 * identity and the narrow payload of {@code TenantReadingAllowanceV1}.
 *
 * @param eventId envelope id, the idempotency key
 * @param aggregateVersion owner's per-tenant sequence, the version guard
 * @param tenantId the tenant the allowance belongs to
 * @param tierCode the reading tier's code
 * @param amount the monthly allowance, at least zero
 * @param currencyCode ISO 4217 code of {@code amount}
 * @param effectiveAt when the allowance applies from
 */
public record ReadingAllowanceEvent(
        @NonNull String eventId,
        long aggregateVersion,
        @NonNull UUID tenantId,
        @NonNull String tierCode,
        @NonNull BigDecimal amount,
        @NonNull String currencyCode,
        @NonNull Instant effectiveAt) {

    /**
     * Parses a serialized envelope; empty when it is not a reading-allowance fact or any required field is missing
     * or malformed (the consumer logs and skips it, recording nothing).
     */
    public static Optional<ReadingAllowanceEvent> parse(@NonNull JsonNode root) {
        if (!TenantEventTypes.READING_ALLOWANCE_CHANGED.equals(root.path("eventType").stringValue(null))) {
            return Optional.empty();
        }
        String eventId = root.path("eventId").stringValue(null);
        JsonNode version = root.path("aggregateVersion");
        if (eventId == null || eventId.isBlank() || !version.isIntegralNumber() || version.longValue() < 0) {
            // The version guard is what keeps a replica monotonic; a fact without one cannot be ordered.
            return Optional.empty();
        }
        JsonNode payload = root.path("payload");
        String tierCode = payload.path("tierCode").stringValue(null);
        String currencyCode = payload.path("monthlyAllowance").path("currencyCode").stringValue(null);
        if (tierCode == null || tierCode.isBlank() || currencyCode == null || !currencyCode.matches("[A-Z]{3}")) {
            return Optional.empty();
        }
        try {
            UUID tenantId = UUID.fromString(payload.path("tenantId").stringValue(""));
            BigDecimal amount = amountOf(payload.path("monthlyAllowance").path("amount"));
            Instant effectiveAt = instantOf(payload.path("effectiveAt"));
            if (amount == null || amount.signum() < 0) {
                return Optional.empty();
            }
            return Optional.of(new ReadingAllowanceEvent(
                    eventId, version.longValue(), tenantId, tierCode, amount, currencyCode, effectiveAt));
        } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    /** ISO-8601 text, or epoch seconds with a fraction when the mapper writes dates as timestamps. */
    private static Instant instantOf(JsonNode node) {
        if (node.isNumber()) {
            BigDecimal seconds = node.decimalValue();
            long whole = seconds.longValue();
            return Instant.ofEpochSecond(
                    whole, seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValue());
        }
        return Instant.parse(node.stringValue(""));
    }

    private static BigDecimal amountOf(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String text = node.stringValue(null);
        return text == null ? null : new BigDecimal(text);
    }
}

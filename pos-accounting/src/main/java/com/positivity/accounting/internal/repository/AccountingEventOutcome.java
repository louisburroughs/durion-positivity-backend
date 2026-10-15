package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.enums.AccountingEventStatus;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What one {@code accounting_event} record says happened to the fact it records, without its payload
 * (#2563): read for a page of rows in one query.
 *
 * @param eventId           the record's id; a UUID v7, so it orders records created in the same instant
 * @param domainKeyId       the fact's domain key, e.g. a settled payment's {@code paymentIntentId}
 * @param status            the record's status
 * @param failureReasonCode why the fact was skipped or held; null when nothing is
 * @param receivedAt        when the record was written
 */
public record AccountingEventOutcome(
        @NonNull UUID eventId,
        @Nullable String domainKeyId,
        @NonNull AccountingEventStatus status,
        @Nullable String failureReasonCode,
        @NonNull Instant receivedAt) {}

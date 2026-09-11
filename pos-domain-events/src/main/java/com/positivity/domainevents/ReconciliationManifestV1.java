package com.positivity.domainevents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Reconciliation manifest for one tenant and one closed time window of a domain's fact topic
 * (ADR-0044 §4, ADR-0062 §3).
 *
 * <p>Owners publish one manifest per tenant per window to {@code {domain}.manifest.v1} (see
 * {@link DomainTopics#manifest(String)}) summarizing the events they published for that tenant
 * whose {@code eventId} (UUIDv7) timestamp falls in {@code [windowStartUtc, windowEndUtc)}. The
 * manifest's envelope and Kafka header carry the same {@code tenantId}, so the consumer's listener
 * runs under it. Consumers recompute the same summary from the rows of that tenant in their
 * idempotency/processing log and, on mismatch, raise a drift metric tagged with the tenant and
 * request an outbox replay of that tenant's events for the window over the owner's command topic.
 * Comparison is stateless and replay is idempotent, so reprocessing a manifest is always safe.
 *
 * <p>Window membership is defined by the timestamp embedded in the event's UUIDv7 {@code eventId}
 * (see {@link UuidV7Timestamps}) — the only timestamp both sides observe identically — never by
 * publish or consume time, and never by tenant. A tenant with no events in a window still gets a
 * zero-count manifest so consumers can also alert on manifest absence.
 *
 * @param tenantId         tenant whose events the manifest summarizes; one manifest per tenant
 *                         per window. Publishers always set it; it is nullable on the wire only
 *                         because manifests published before it existed (2026-09-11) carry none
 *                         (additive-nullable evolution within schema version 1, ADR-0044 §3) —
 *                         consumers read those through {@link #tenantIdOr(UUID)}
 * @param windowStartUtc   inclusive start of the reconciled window
 * @param windowEndUtc     exclusive end of the reconciled window
 * @param eventCount       number of events published in the window
 * @param eventIdsChecksum checksum over the window's eventIds, per {@link #checksumOf(Collection)}
 * @param eventTypeCounts  optional per-eventType counts for drift diagnostics; may be null
 */
public record ReconciliationManifestV1(
        @Nullable UUID tenantId,
        @NonNull Instant windowStartUtc,
        @NonNull Instant windowEndUtc,
        long eventCount,
        @NonNull String eventIdsChecksum,
        @Nullable Map<String, Long> eventTypeCounts) {

    /** Event-type suffix of manifest envelopes: {@code {domain}.reconciliation.manifest}. */
    public static final String EVENT_TYPE_SUFFIX = "reconciliation.manifest";

    /** Envelope schema version of manifests, shared by every domain that publishes them. */
    public static final int SCHEMA_VERSION = 1;

    public ReconciliationManifestV1 {
        if (windowStartUtc == null || windowEndUtc == null) {
            throw new IllegalArgumentException("windowStartUtc and windowEndUtc must not be null");
        }
        if (!windowStartUtc.isBefore(windowEndUtc)) {
            throw new IllegalArgumentException(
                    "windowStartUtc must be before windowEndUtc but was: " + windowStartUtc + " / " + windowEndUtc);
        }
        if (eventCount < 0) {
            throw new IllegalArgumentException("eventCount must be >= 0 but was: " + eventCount);
        }
        if (eventIdsChecksum == null || eventIdsChecksum.isBlank()) {
            throw new IllegalArgumentException("eventIdsChecksum must not be blank");
        }
    }

    /**
     * The tenant this manifest is for. A manifest published before the field existed carries none:
     * it summarised every tenant's rows as a platform-tenant record (the plan's WS4-1 stopgap) and
     * rode the platform tenant's Kafka header, so a consumer passes the platform tenant id as
     * {@code legacyTenantId} and compares it against that tenant's ledger, exactly as it did then.
     */
    public @NonNull UUID tenantIdOr(@NonNull UUID legacyTenantId) {
        return tenantId != null ? tenantId : legacyTenantId;
    }

    /** Envelope eventType for a domain's manifests, e.g. {@code workorder.reconciliation.manifest}. */
    public static @NonNull String eventTypeFor(@NonNull String domain) {
        return domain + "." + EVENT_TYPE_SUFFIX;
    }

    /**
     * Canonical checksum both sides must use: lowercase hex SHA-256 over the lexicographically
     * sorted eventId strings joined with {@code \n}. Sorting makes the checksum independent of
     * publish/consume order.
     */
    public static @NonNull String checksumOf(@NonNull Collection<String> eventIds) {
        List<String> sorted = new ArrayList<>(eventIds);
        sorted.sort(null);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        boolean first = true;
        for (String eventId : sorted) {
            if (!first) {
                digest.update((byte) '\n');
            }
            digest.update(eventId.getBytes(StandardCharsets.UTF_8));
            first = false;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** True when this manifest matches the consumer-side count and checksum. */
    public boolean matches(long observedCount, @NonNull String observedChecksum) {
        return eventCount == observedCount && eventIdsChecksum.equals(observedChecksum);
    }
}

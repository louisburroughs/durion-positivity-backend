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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Reconciliation manifest for one closed time window of a domain's fact topic (ADR-0044 §4).
 *
 * <p>Owners publish one manifest per window to {@code {domain}.manifest.v1} (see
 * {@link DomainTopics#manifest(String)}) summarizing the events they published whose
 * {@code eventId} (UUIDv7) timestamp falls in {@code [windowStartUtc, windowEndUtc)}. Consumers
 * recompute the same summary from their idempotency/processing log and, on mismatch, raise a drift
 * metric and request an outbox replay for the window over the owner's command topic. Comparison is
 * stateless and replay is idempotent, so reprocessing a manifest is always safe.
 *
 * <p>Window membership is defined by the timestamp embedded in the event's UUIDv7 {@code eventId}
 * (see {@link UuidV7Timestamps}) — the only timestamp both sides observe identically — never by
 * publish or consume time. A zero-event window still gets a manifest so consumers can also alert
 * on manifest absence.
 *
 * @param windowStartUtc   inclusive start of the reconciled window
 * @param windowEndUtc     exclusive end of the reconciled window
 * @param eventCount       number of events published in the window
 * @param eventIdsChecksum checksum over the window's eventIds, per {@link #checksumOf(Collection)}
 * @param eventTypeCounts  optional per-eventType counts for drift diagnostics; may be null
 */
public record ReconciliationManifestV1(
        @NonNull Instant windowStartUtc,
        @NonNull Instant windowEndUtc,
        long eventCount,
        @NonNull String eventIdsChecksum,
        @Nullable Map<String, Long> eventTypeCounts) {

    /** Event-type suffix of manifest envelopes: {@code {domain}.reconciliation.manifest}. */
    public static final String EVENT_TYPE_SUFFIX = "reconciliation.manifest";

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

package com.positivity.domainevents;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Timestamp arithmetic on UUIDv7 identifiers (RFC 9562) for reconciliation windowing
 * (ADR-0044 §4).
 *
 * <p>A UUIDv7's top 48 bits are the Unix epoch milliseconds at generation time, so the
 * {@code eventId} itself tells both producer and consumer which reconciliation window an event
 * belongs to — no shared clock or extra column needed. The canonical lowercase string form sorts
 * lexicographically by that timestamp, which lets consumers range-scan a varchar eventId column
 * with the bounds from {@link #minStringAt(Instant)}.
 */
public final class UuidV7Timestamps {

    private static final long MILLIS_MASK = 0xFFFF_FFFF_FFFFL;

    private UuidV7Timestamps() {}

    /** The generation instant (millisecond precision) embedded in a UUIDv7. */
    public static @NonNull Instant instantOf(@NonNull UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a UUIDv7 (version " + uuid.version() + "): " + uuid);
        }
        return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
    }

    /** True when the UUIDv7's embedded timestamp is in {@code [startInclusive, endExclusive)}. */
    public static boolean isInWindow(
            @NonNull UUID uuid, @NonNull Instant startInclusive, @NonNull Instant endExclusive) {
        Instant at = instantOf(uuid);
        return !at.isBefore(startInclusive) && at.isBefore(endExclusive);
    }

    /**
     * The smallest canonical UUIDv7 string with the given timestamp: use as an inclusive lower /
     * exclusive upper bound when range-scanning stored eventId strings by window.
     */
    public static @NonNull String minStringAt(@NonNull Instant timestamp) {
        long millis = timestamp.toEpochMilli();
        if ((millis & ~MILLIS_MASK) != 0) {
            throw new IllegalArgumentException("Timestamp out of UUIDv7 range: " + timestamp);
        }
        // All-zero version/variant/random bits sort before every real UUIDv7 sharing the timestamp.
        return String.format("%08x-%04x-0000-0000-000000000000", millis >>> 16, millis & 0xFFFF);
    }
}

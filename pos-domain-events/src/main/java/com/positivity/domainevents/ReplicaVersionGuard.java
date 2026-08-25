package com.positivity.domainevents;

/**
 * The platform's canonical staleness rule for a {@code catalog.events.v1} consumer's replica
 * {@code aggregateVersion} guard (#1486).
 *
 * <p>pos-catalog's {@code aggregateVersion} is a JPA {@code @Version}-backed counter that
 * strictly advances on every write to the aggregate; it was seeded from the legacy
 * {@code updatedAt} epoch-millis values so magnitudes continue seamlessly across the migration.
 * Because the publisher's contract is strictly-advancing, two facts for the same aggregate can
 * carry the same version only when they describe the same content — an equal version is never
 * evidence of a newer or older state, so a consumer that skips on it is discarding nothing.
 *
 * <p>That is also what makes {@code POST .../facts/replay} work as a repair tool: a replayed fact
 * deliberately carries the same version as the state it describes, specifically so it can rewrite
 * a replica row that already holds that version number but wrong or missing data. A consumer that
 * skips on equal ({@code >=}) turns replay into a silent no-op — the replica keeps the version it
 * already had and the bad row is never corrected. That silent no-op was #1486's operational trap:
 * an operator would run replay, see it "succeed", and the replica would stay broken. Applying on
 * equal costs nothing for live traffic (it is an idempotent overwrite with identical content) and
 * is the only thing that makes replay-as-repair actually repair anything.
 *
 * <p>Every {@code catalog.events.v1} consumer's stale guard must use this class rather than
 * re-deriving the comparison, so the rule stays in exactly one place.
 */
public final class ReplicaVersionGuard {

    private ReplicaVersionGuard() {}

    /**
     * Whether a held replica version is stale relative to an incoming fact's version.
     *
     * <p>Returns {@code true} only when {@code heldVersion} is strictly greater than
     * {@code incomingVersion} — the held row describes a state newer than the fact, so the fact
     * must be discarded. An equal version is NOT stale: per the strictly-advancing publisher
     * contract (see the class javadoc), equal means identical content, and applying is both a
     * safe no-op for live traffic and required for {@code facts/replay} to repair a replica.
     *
     * @param heldVersion     {@code aggregateVersion} currently stored on the replica row, or the
     *                        version implied by "no row held" per the caller's own convention
     * @param incomingVersion {@code aggregateVersion} carried by the incoming fact
     * @return {@code true} only if the held version is strictly newer than the incoming one
     */
    public static boolean isStale(long heldVersion, long incomingVersion) {
        return heldVersion > incomingVersion;
    }
}

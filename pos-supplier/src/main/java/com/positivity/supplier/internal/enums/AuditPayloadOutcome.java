package com.positivity.supplier.internal.enums;

/**
 * What a recorded payload read actually yielded (ADR-0050 §7).
 *
 * <p>A failed or empty read is still an access, so all three are recorded rather than only the
 * successful one — the alternative would leave the two most interesting cases invisible.
 */
public enum AuditPayloadOutcome {
    /** The stored envelope decrypted and the content was served. */
    DECRYPTED,

    /**
     * Content existed but could not be decrypted — malformed envelope, a key id this deployment has no
     * key for, or a failed GCM tag. Still an attempt to reach commercial content, and the case an
     * investigator is most likely to be looking for, so it is recorded rather than swallowed.
     */
    UNREADABLE,

    /**
     * There was nothing to read: a {@code METADATA_ONLY} binding captured none, the exchange carried no
     * body, or retention has already nulled the content. Recorded honestly rather than as
     * {@link #DECRYPTED} — an evidentiary row must not claim a disclosure that never happened.
     */
    NOT_CAPTURED
}

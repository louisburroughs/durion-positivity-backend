package com.positivity.supplier.service.model;

/**
 * What a recorded payload read yielded (ADR-0050 §7). A failed or empty read is still an access, so all
 * three outcomes appear in the trail rather than only the successful one.
 */
public enum AuditPayloadOutcome {
    /** The stored content decrypted and was served to the caller. */
    DECRYPTED,

    /**
     * Content existed but could not be decrypted — a malformed envelope, a key this deployment does not
     * hold, or a failed integrity tag. Recorded because an authorised caller still reached for commercial
     * content, and because this is the case an investigation is most likely looking for.
     */
    UNREADABLE,

    /**
     * There was nothing to read: the binding captured no payloads, the exchange carried no body, or
     * retention has already nulled the content. Distinguished from {@code DECRYPTED} so the trail never
     * claims a disclosure that did not happen.
     */
    NOT_CAPTURED
}

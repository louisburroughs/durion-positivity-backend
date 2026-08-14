package com.positivity.supplier.internal.order.service;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Derives the wire {@code DocumentID}/{@code CustomerReference} from a transmission intent
 * (ADR-0052 §1).
 *
 * <h2>Derived, never generated</h2>
 *
 * The document id is a pure function of {@code transmissionIntentId}. Nothing here consults a
 * clock, a counter or a random source, which is what makes the guarantee testable: retrying one
 * intent always presents the same reference, and a new intent always presents a different one.
 * A generated id would make the first property depend on remembering to persist it before the
 * first send — exactly the step a crash skips.
 *
 * <h2>Why 35 characters, and why it matters</h2>
 *
 * The EDIWheel C1.0 {@code CustomerReference/DocumentID} is capped at 35 characters. A UUID's
 * canonical form is 36, so the hyphens are dropped and a three-letter marker prefixed, landing
 * exactly on the limit: {@code DUR} + 32 hex digits. Sending 36 would risk silent truncation at
 * the vendor, and a truncated reference is a reference an order-status query can no longer find
 * — which turns every ambiguous outcome on that vendor into a manual review.
 */
public final class TransmissionDocumentId {

    /** Marks the reference as Durion's in a vendor's own screens and support calls. */
    static final String PREFIX = "DUR";

    /** The C1.0 cap on {@code CustomerReference/DocumentID}. */
    static final int MAX_LENGTH = 35;

    private TransmissionDocumentId() {}

    /**
     * The document id for a transmission intent.
     *
     * @param transmissionIntentId the intent's immutable id
     * @return a 35-character reference, stable for the life of the intent
     */
    @NonNull
    public static String forIntent(@NonNull UUID transmissionIntentId) {
        Objects.requireNonNull(transmissionIntentId, "transmissionIntentId must not be null");
        String value = PREFIX + transmissionIntentId.toString().replace("-", "").toUpperCase(Locale.ROOT);
        if (value.length() != MAX_LENGTH) {
            // Unreachable for a real UUID; asserted anyway because the failure mode of being wrong
            // here is silent truncation at a vendor, which surfaces weeks later as an unanswerable
            // status query.
            throw new IllegalStateException(
                    "Derived document id must be " + MAX_LENGTH + " characters but was " + value.length());
        }
        return value;
    }
}

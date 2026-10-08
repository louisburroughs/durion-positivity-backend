package com.positivity.supplier.internal.vendor.service.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a committed reveal produced (#2621; ADR-0072 Decision 4, "Every audited outcome commits"). The transactional
 * service returns this instead of throwing after it has written the audit row, because an unchecked exception would
 * roll that row back. The controller receives it only after the transaction committed, and only then maps it to
 * 200, 400 or 500.
 *
 * @param outcome what happened; its audit row is committed
 * @param view the revealed registration on {@code REVEALED}; {@code null} otherwise
 * @param failure the decryption failure kind on {@code UNREADABLE} ({@code MALFORMED_ENVELOPE},
 *     {@code UNKNOWN_KEY_ID} or {@code AUTHENTICATION_FAILED}); {@code null} otherwise
 * @param keyId the key id the envelope named on {@code UNREADABLE}, when it named one
 */
public record TaxIdRevealResult(
        @NonNull TaxIdRevealOutcome outcome,
        @Nullable TaxIdRevealView view,
        @Nullable String failure,
        @Nullable String keyId) {

    public TaxIdRevealResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if ((outcome == TaxIdRevealOutcome.REVEALED) != (view != null)) {
            throw new IllegalArgumentException("a view is present exactly on REVEALED");
        }
    }

    public static TaxIdRevealResult revealed(@NonNull TaxIdRevealView view) {
        return new TaxIdRevealResult(TaxIdRevealOutcome.REVEALED, view, null, null);
    }

    public static TaxIdRevealResult reasonRejected() {
        return new TaxIdRevealResult(TaxIdRevealOutcome.REASON_REJECTED, null, null, null);
    }

    public static TaxIdRevealResult unreadable(@NonNull String failure, @Nullable String keyId) {
        return new TaxIdRevealResult(TaxIdRevealOutcome.UNREADABLE, null, failure, keyId);
    }
}

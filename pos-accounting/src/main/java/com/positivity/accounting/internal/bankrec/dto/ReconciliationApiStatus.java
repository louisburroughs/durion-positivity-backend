package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;

/**
 * The reconciliation status values the F2 API serves (story S1, #2300).
 *
 * <p>{@link ReconciliationStatus} carries the full SPEC §3.8 value set from story S1 on, but no
 * transition reaches the new values until story S5, which also opens them on the API. Until then the
 * served set stays exactly F2's, so the published contract and the SDK do not change.
 */
public enum ReconciliationApiStatus {
    IN_PROGRESS(ReconciliationStatus.IN_PROGRESS),
    FINALIZED(ReconciliationStatus.FINALIZED),
    CANCELLED(ReconciliationStatus.CANCELLED);

    private final ReconciliationStatus domain;

    ReconciliationApiStatus(ReconciliationStatus domain) {
        this.domain = domain;
    }

    /** The stored status this API value filters on. */
    public ReconciliationStatus toDomain() {
        return domain;
    }

    /**
     * The API value of a stored status.
     *
     * @throws IllegalStateException for a status no story-S1 transition can produce
     */
    public static ReconciliationApiStatus from(ReconciliationStatus status) {
        if (status == null) {
            return null;
        }
        for (ReconciliationApiStatus value : values()) {
            if (value.domain == status) {
                return value;
            }
        }
        throw new IllegalStateException(
                "Reconciliation status " + status + " is not served by the API before story S5");
    }
}

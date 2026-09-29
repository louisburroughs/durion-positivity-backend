package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;

/**
 * The reconciliation status values the API serves (stories S1 #2300, S5 #2304): the full SPEC §3.8 set,
 * which story S5 makes reachable (submit, approve, invalidation, supersession, cancel).
 */
public enum ReconciliationApiStatus {
    IN_PROGRESS(ReconciliationStatus.IN_PROGRESS),
    SUBMITTED(ReconciliationStatus.SUBMITTED),
    FINALIZED(ReconciliationStatus.FINALIZED),
    INVALIDATED(ReconciliationStatus.INVALIDATED),
    SUPERSEDED(ReconciliationStatus.SUPERSEDED),
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
     * @throws IllegalStateException for a status the API does not serve
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
        throw new IllegalStateException("Reconciliation status " + status + " is not served by the API");
    }
}

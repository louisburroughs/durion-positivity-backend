package com.positivity.customer.service;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Verifies invariant ADR-0015 I1: every {@code person_party.person_id} references
 * an existing {@code pos-people.person.id}. Read-only; reports orphan links so
 * they can be remediated before person_party is demoted to a thin link (Phase 3).
 */
public interface PersonLinkReconciliationService {

    /**
     * Check all linked person ids against pos-people.
     *
     * @return reconciliation report
     */
    @NonNull
    PersonLinkReport reconcile();

    /**
     * Outcome of a reconciliation pass.
     *
     * @param totalLinks          distinct linked person ids
     * @param resolved            ids that exist in pos-people
     * @param unresolvedPersonIds ids with no matching pos-people person (orphans)
     * @param posPeopleReachable  false if pos-people could not be reached, in which
     *                            case the resolved/unresolved counts are not meaningful
     */
    record PersonLinkReport(
            int totalLinks, int resolved, @NonNull List<UUID> unresolvedPersonIds, boolean posPeopleReachable) {
        public boolean isHealthy() {
            return posPeopleReachable && unresolvedPersonIds.isEmpty();
        }
    }
}

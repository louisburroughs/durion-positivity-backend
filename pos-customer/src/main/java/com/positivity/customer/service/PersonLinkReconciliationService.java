package com.positivity.customer.service;

import io.swagger.v3.oas.annotations.media.Schema;
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
    @Schema(description = "Outcome of a person-link reconciliation pass against pos-people")
    record PersonLinkReport(
            @Schema(description = "Count of distinct linked person ids checked", example = "120", requiredMode = Schema.RequiredMode.REQUIRED)
                    int totalLinks,
            @Schema(
                            description = "Count of linked ids that exist in pos-people",
                            example = "118",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    int resolved,
            @Schema(
                            description = "Linked person ids with no matching pos-people person (orphans)",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NonNull List<UUID> unresolvedPersonIds,
            @Schema(
                            description = "False if pos-people could not be reached, in which case resolved/unresolved counts are not meaningful",
                            example = "true",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    boolean posPeopleReachable) {
        @Schema(
                description = "Whether reconciliation is healthy: pos-people reachable and no unresolved orphan links",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        public boolean isHealthy() {
            return posPeopleReachable && unresolvedPersonIds.isEmpty();
        }
    }
}

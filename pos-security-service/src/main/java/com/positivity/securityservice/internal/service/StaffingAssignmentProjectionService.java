package com.positivity.securityservice.internal.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Query side of the staffing-assignment read model (ADR-0061 §1, #1867): which location nodes is
 * a person assigned to, effective on a given date?
 *
 * <p>Both methods evaluate the same predicate — {@code status = ACTIVE},
 * {@code effectiveFrom <= asOf} and ({@code effectiveTo} null or {@code >= asOf}) — over the rows
 * pos-people published. Callers resolving "now" must derive {@code asOf} from an explicit clock
 * and zone; nothing here reads system time.
 */
public interface StaffingAssignmentProjectionService {

    /**
     * Distinct assigned node ids (shop or parent node, verbatim — never expanded) effective on
     * {@code asOf}, in a deterministic order. Every active assignment counts; {@code is_primary}
     * does not narrow the set. Exceeding the configured node cap is reported, not truncated.
     */
    @NonNull
    List<UUID> assignedLocationIds(@NonNull UUID personId, @NonNull LocalDate asOf);

    /**
     * Earliest {@code effectiveTo} among the assignments effective on {@code asOf}; empty when
     * none of them ends (or none exist). Input to the token {@code exp} clamp (ADR-0061 §4, #1873).
     */
    @NonNull
    Optional<LocalDate> earliestEffectiveTo(@NonNull UUID personId, @NonNull LocalDate asOf);
}

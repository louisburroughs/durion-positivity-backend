package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * What every reconciliation command shares (story S4, #2303): finding the reconciliation, refusing a
 * mutation on a {@code FINALIZED} one, the acting user (ADR-0018), and recomputing the live terms and
 * storing them on the row after every mutation (§3.7).
 */
@Component
@RequiredArgsConstructor
public class ReconciliationSupport {

    private static final String SYSTEM = "SYSTEM";

    private final BankReconciliationRepository reconciliations;
    private final ReconciliationCalculator calculator;
    private final Clock clock;

    /** The reconciliation, or 404 {@code RECONCILIATION_NOT_FOUND}. */
    public @NonNull BankReconciliation require(@NonNull UUID reconciliationId) {
        return reconciliations
                .findById(reconciliationId)
                .orElseThrow(
                        () -> new ReconciliationNotFoundException("Reconciliation not found: " + reconciliationId));
    }

    /**
     * The reconciliation, open to mutation: {@code FINALIZED} answers 409
     * {@code RECONCILIATION_ALREADY_FINALIZED} (§4.9; {@code RECONCILIATION_NOT_EDITABLE} for the other
     * terminal states arrives with S5, which makes them reachable).
     */
    public @NonNull BankReconciliation requireOpen(@NonNull UUID reconciliationId) {
        BankReconciliation reconciliation = require(reconciliationId);
        if (reconciliation.getStatus() == ReconciliationStatus.FINALIZED) {
            throw new ReconciliationAlreadyFinalizedException(
                    "Reconciliation " + reconciliationId + " is already FINALIZED");
        }
        return reconciliation;
    }

    /** Recomputes the live terms and stores them on the row; returns the snapshot. */
    public @NonNull ReconciliationSnapshot refresh(@NonNull BankReconciliation reconciliation) {
        ReconciliationSnapshot snapshot = calculator.compute(reconciliation);
        ReconciliationCalculator.apply(reconciliation, snapshot);
        reconciliations.save(reconciliation);
        return snapshot;
    }

    /** The acting user, or {@code SYSTEM} outside a request. */
    public @NonNull String currentUser() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }

    /** Now, on the shared clock (ADR-0024). */
    public @NonNull Instant now() {
        return Instant.now(clock);
    }

    /** Today, on the shared clock. */
    public @NonNull LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Whether the caller holds {@code authority}. */
    public boolean hasAuthority(@NonNull String authority) {
        return SecurityContextHelper.isAuthenticated() && SecurityContextHelper.hasAuthority(authority);
    }
}

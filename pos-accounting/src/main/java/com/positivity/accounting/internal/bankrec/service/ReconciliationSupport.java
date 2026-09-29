package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What every reconciliation command shares (stories S4 #2303, S5 #2304): finding and locking the
 * reconciliation, refusing a mutation in a status that does not allow it, the version check, the acting user
 * (ADR-0018), and recomputing the live terms and storing them on the row after every mutation (§3.7).
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
     * The reconciliation, open to the preparer's mutations: only {@code IN_PROGRESS} (§3.8). {@code FINALIZED}
     * answers 409 {@code RECONCILIATION_ALREADY_FINALIZED}; {@code SUBMITTED} (return it first), {@code
     * INVALIDATED}, {@code SUPERSEDED} and {@code CANCELLED} answer 409 {@code RECONCILIATION_NOT_EDITABLE}
     * (§4.9; S5, #2304).
     */
    public @NonNull BankReconciliation requireOpen(@NonNull UUID reconciliationId) {
        BankReconciliation reconciliation = require(reconciliationId);
        requireStatus(reconciliation, ReconciliationStatus.IN_PROGRESS);
        return reconciliation;
    }

    /**
     * Refuses a reconciliation not in one of {@code allowed}: {@code FINALIZED} with 409 {@code
     * RECONCILIATION_ALREADY_FINALIZED}, any other status with 409 {@code RECONCILIATION_NOT_EDITABLE}.
     */
    public static void requireStatus(
            @NonNull BankReconciliation reconciliation, @NonNull ReconciliationStatus... allowed) {
        ReconciliationStatus status = reconciliation.getStatus();
        for (ReconciliationStatus ok : allowed) {
            if (status == ok) {
                return;
            }
        }
        if (status == ReconciliationStatus.FINALIZED) {
            throw new ReconciliationAlreadyFinalizedException(
                    "Reconciliation " + reconciliation.getReconciliationId() + " is already FINALIZED");
        }
        throw new BankRecException(
                BankRecErrorCode.RECONCILIATION_NOT_EDITABLE,
                "Reconciliation " + reconciliation.getReconciliationId() + " is " + status
                        + (status == ReconciliationStatus.SUBMITTED
                                ? "; the approver returns it to the preparer before it changes"
                                : " and can no longer change"));
    }

    /** The reconciliation, row-locked {@code FOR UPDATE} for the transaction (§4.9, I3), or 404. */
    public @NonNull BankReconciliation lock(@NonNull UUID reconciliationId) {
        return reconciliations
                .lockById(reconciliationId)
                .orElseThrow(
                        () -> new ReconciliationNotFoundException("Reconciliation not found: " + reconciliationId));
    }

    /** A stale {@code version} answers 409 {@code OPTIMISTIC_LOCK} (§6.3); an absent one is not checked. */
    public static void requireVersion(@NonNull BankReconciliation reconciliation, @Nullable Long version) {
        if (version != null && !Objects.equals(version, reconciliation.getVersion())) {
            throw BankRecException.field(
                    BankRecErrorCode.OPTIMISTIC_LOCK,
                    "Reconciliation " + reconciliation.getReconciliationId() + " was changed by another request;"
                            + " reload it and retry",
                    "version",
                    "current version is " + reconciliation.getVersion());
        }
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

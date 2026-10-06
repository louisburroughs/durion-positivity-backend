package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationsPage;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

/**
 * The payment-specific read of applications nobody made by hand (CAP:550 S2, #2503): the "Matched
 * automatically this week" list with Undo (S6) and the home footer's count (S4). S19's work-item
 * model lists the same rows. Read-only; the tenant comes from the security context and row-level
 * security.
 */
public interface AutomaticPaymentApplicationQueryService {

    /**
     * A page of applications with a source other than {@code MANUAL} made at or after {@code since},
     * newest first (ties by application id), reversed ones included and flagged.
     *
     * @param since   inclusive lower bound of the application date (the controller bounds it)
     * @param page    page index, 0 or more
     * @param size    page size, 1 to 100
     * @param canUndo whether the caller holds {@code accounting:payment:reverse}, which offers {@code UNDO}
     */
    @NonNull
    AutomaticPaymentApplicationsPage listAutomatic(@NonNull Instant since, int page, int size, boolean canUndo);
}

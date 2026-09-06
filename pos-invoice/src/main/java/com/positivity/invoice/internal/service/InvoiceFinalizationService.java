package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.FinalizationEligibilityResult;
import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Public service contract for controlled invoice finalization and reversion.
 * Story #13 scaffold — implementations must reside in internal.service.
 */
public interface InvoiceFinalizationService {

    @NonNull
    FinalizationEligibilityResult checkEligibility(@NonNull UUID invoiceId);

    @NonNull
    InvoiceDetailsResponse completeInvoice(@NonNull UUID invoiceId, @NonNull FinalizationRequest request);

    @NonNull
    InvoiceDetailsResponse revert(@NonNull UUID invoiceId, @NonNull String managerApprovalCode, @NonNull String reason);

    /**
     * Applies pos-accounting's {@code accounting.invoice.gl-posted} fact (#1843): a FINALIZED
     * invoice whose {@code finalizedAt} matches the fact becomes POSTED with {@code glEntryId}
     * set to the journal entry id. Every other state is skipped (logged), never rejected.
     *
     * @param invoiceId   the invoice the journal entry was posted for
     * @param glEntryId   the posted revenue journal entry id
     * @param finalizedAt the finalization instance the entry belongs to
     */
    void markPosted(@NonNull UUID invoiceId, @NonNull UUID glEntryId, @NonNull Instant finalizedAt);
}

package com.positivity.invoice.service;

import com.positivity.invoice.internal.dto.FinalizationEligibilityResult;
import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Public service contract for controlled invoice finalization and reversion.
 * Story #13 scaffold — implementations must reside in internal.service.
 */
public interface InvoiceFinalizationService {

    @NonNull
    FinalizationEligibilityResult checkEligibility(@NonNull UUID invoiceId);

    @NonNull
    InvoiceDetailsResponse finalize(@NonNull UUID invoiceId, @NonNull FinalizationRequest request);

    @NonNull
    InvoiceDetailsResponse revert(@NonNull UUID invoiceId,
            @NonNull String managerApprovalCode,
            @NonNull String reason);
}

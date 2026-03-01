package com.positivity.invoice.service;

import com.positivity.invoice.internal.dto.AdjustmentRequest;
import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Public invoice service API for invoice generation and lifecycle operations.
 */
public interface InvoiceService {

    @NonNull
    InvoiceGenerationResponse createInvoice(@NonNull InvoiceGenerationRequest request);

    @NonNull
    InvoiceGenerationResponse createInvoice(@NonNull InvoiceCreationRequest request);

    @NonNull
    InvoiceDetailsResponse getInvoice(@NonNull UUID invoiceId);

    @NonNull
    InvoiceDetailsResponse applyAdjustment(@NonNull UUID invoiceId, @NonNull AdjustmentRequest request);

    @NonNull
    InvoiceDetailsResponse finalizeInvoice(@NonNull UUID invoiceId);

    /** Permission-aware finalization — Story #13 scaffold. */
    @NonNull
    InvoiceDetailsResponse finalizeInvoice(@NonNull UUID invoiceId, @NonNull FinalizationRequest request);

    /** Revert a finalized invoice — Story #13 scaffold. */
    @NonNull
    InvoiceDetailsResponse revertFinalization(@NonNull UUID invoiceId,
            @NonNull String managerApprovalCode,
            @NonNull String reason);
}

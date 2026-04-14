package com.positivity.invoice.service;

import com.positivity.invoice.internal.dto.AdjustmentRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

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
}

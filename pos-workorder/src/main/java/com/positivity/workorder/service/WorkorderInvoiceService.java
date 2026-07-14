package com.positivity.workorder.service;

import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkorderInvoiceService {

    /** Response status while asynchronous invoice generation is in flight (ADR-0044 R4, #900). */
    String STATUS_PENDING = "PENDING";

    @NonNull
    InvoiceGenerationResponse generateInvoice(@NonNull UUID workorderId, @Nullable String idempotencyKey);
}

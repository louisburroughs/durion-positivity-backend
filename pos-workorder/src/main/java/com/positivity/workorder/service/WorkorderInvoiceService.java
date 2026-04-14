package com.positivity.workorder.service;

import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkorderInvoiceService {

    @NonNull
    InvoiceGenerationResponse generateInvoice(@NonNull UUID workorderId, @Nullable String idempotencyKey);
}

package com.positivity.accounting.service;

import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface InvoiceRegenerationService {

    @NonNull
    InvoiceGenerationResponse regenerateInvoiceFromWorkorder(
            @NonNull UUID workorderId, @Nullable String idempotencyKey);
}

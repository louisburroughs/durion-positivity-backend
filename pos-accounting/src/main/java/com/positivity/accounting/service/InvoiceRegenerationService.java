package com.positivity.accounting.service;

import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.positivity.shared.dto.InvoiceGenerationResponse;

public interface InvoiceRegenerationService {

    @NonNull
    InvoiceGenerationResponse regenerateInvoiceFromWorkorder(
            @NonNull UUID workorderId,
            @Nullable String idempotencyKey);
}

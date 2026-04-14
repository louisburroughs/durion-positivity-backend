package com.positivity.accounting.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
public class RegenerateInvoiceFromWorkorderRequest {

    @NotNull
    private UUID workorderId;

    private String idempotencyKey;
}

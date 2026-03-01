package com.positivity.accounting.internal.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RegenerateInvoiceFromWorkorderRequest {

    @NotNull
    private UUID workorderId;

    private String idempotencyKey;
}
